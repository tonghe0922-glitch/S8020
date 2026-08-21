package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.workflow.CoreWorkflowIdempotency;
import cn.shangjingu.platform.workflow.FailClosedTransitionConditionEvaluator;
import cn.shangjingu.platform.workflow.JdbcWorkflowRuntimeRepository;
import cn.shangjingu.platform.workflow.WorkflowException;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase05WorkflowFinalDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID BOOTSTRAP_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000581");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000582");
    private static final String API_PASSWORD = "p05_c8_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger VERSION_SEQUENCE = new AtomicInteger(9950);
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static UUID tenantADefinition;
    private static UUID tenantBDefinition;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-c8-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        seedRlsTenants();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void canonicalWorkflowTablesUseApprovedRlsAndRuntimeCannotCrossTenant() throws Exception {
        for (String qualified : List.of(
                "workflow.wf_definition",
                "workflow.wf_version",
                "workflow.wf_node",
                "workflow.wf_transition",
                "workflow.wf_instance",
                "workflow.wf_task",
                "workflow.wf_action_log",
                "workflow.wf_form_definition",
                "workflow.wf_submission",
                "workflow.wf_submission_value",
                "workflow.wf_sla_policy",
                "workflow.wf_orchestration_instance",
                "workflow.wf_orchestration_instance_item",
                "workflow.wf_orchestration_link")) {
            assertApprovedRls(qualified);
        }

        long tenantASeesA = Phase05WorkflowFinalDatabaseIT.<Long>inApiTenant(TENANT_A, connection -> count(
                connection, "select count(*) from workflow.wf_definition where process_code='C8RLSA'"));
        long tenantASeesB = Phase05WorkflowFinalDatabaseIT.<Long>inApiTenant(TENANT_A, connection -> count(
                connection, "select count(*) from workflow.wf_definition where process_code='C8RLSB'"));
        long tenantBSeesB = Phase05WorkflowFinalDatabaseIT.<Long>inApiTenant(TENANT_B, connection -> count(
                connection, "select count(*) from workflow.wf_definition where process_code='C8RLSB'"));
        long tenantBSeesA = Phase05WorkflowFinalDatabaseIT.<Long>inApiTenant(TENANT_B, connection -> count(
                connection, "select count(*) from workflow.wf_definition where process_code='C8RLSA'"));
        assertEquals(1L, tenantASeesA);
        assertEquals(0L, tenantASeesB);
        assertEquals(1L, tenantBSeesB);
        assertEquals(0L, tenantBSeesA);

        int changed = inApiTenant(TENANT_A, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "update workflow.wf_definition set process_name='forbidden-cross-tenant' where id=?")) {
                statement.setObject(1, tenantBDefinition);
                return statement.executeUpdate();
            } catch (SQLException ex) {
                throw new DatabaseTestException(ex);
            }
        });
        assertEquals(0, changed, "cross-tenant workflow update must be filtered by approved RLS");
        assertEquals("C8 RLS Tenant B", scalarString(
                "select process_name from workflow.wf_definition where id='" + tenantBDefinition + "'"));

        try (Connection connection = admin("sjg_oms")) {
            assertTrue(hasTablePrivilege(connection, "sjg_api_runtime", "workflow.wf_instance", "SELECT"));
            assertTrue(hasTablePrivilege(connection, "sjg_api_runtime", "workflow.wf_task", "UPDATE"));
            assertFalse(hasSchemaPrivilege(connection, "sjg_api_runtime", "workflow", "CREATE"));
        }
    }

    @Test
    void competingRuntimeActionsCommitExactlyOneMutationAndRollbackLoserIdempotency() throws Exception {
        RuntimeSeed seed = seedRuntimeGraph();
        RuntimeHandle bootstrap = runtime();
        WorkflowRuntimeService.Result started = bootstrap.tx().execute(status -> bootstrap.service().start(startCommand(
                seed, "c8-concurrency-start", "Concurrency workflow")));
        assertNotNull(started);
        WorkflowRuntimeService.Result review = bootstrap.tx().execute(status -> bootstrap.service().act(actionCommand(
                seed.actorId(), started.instance().id(), null, "START", "SUBMIT", "c8-concurrency-submit", "submit")));
        assertNotNull(review);
        assertNotNull(review.task());
        assign(review.task().id(), seed.actorId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> first = pool.submit(() -> concurrentApprove(
                    seed.actorId(), review.instance().id(), review.task().id(), "c8-race-action-a", ready, fire));
            Future<Attempt> second = pool.submit(() -> concurrentApprove(
                    seed.actorId(), review.instance().id(), review.task().id(), "c8-race-action-b", ready, fire));
            assertTrue(ready.await(20, TimeUnit.SECONDS), "competing transactions did not become ready");
            fire.countDown();

            Attempt a = first.get(60, TimeUnit.SECONDS);
            Attempt b = second.get(60, TimeUnit.SECONDS);
            long successes = List.of(a, b).stream().filter(Attempt::success).count();
            assertEquals(1L, successes, "exactly one competing workflow action may commit");
            Attempt loser = a.success() ? b : a;
            assertTrue(
                    loser.code() == WorkflowException.Code.ILLEGAL_ACTION
                            || loser.code() == WorkflowException.Code.STALE_VERSION,
                    () -> "unexpected competing action failure: " + loser.code());
        } finally {
            pool.shutdownNow();
        }

        assertEquals("COMPLETED", scalarString(
                "select status from workflow.wf_instance where id='" + review.instance().id() + "'"));
        assertEquals("END_OK", scalarString(
                "select current_node_code from workflow.wf_instance where id='" + review.instance().id() + "'"));
        assertEquals("COMPLETED", scalarString(
                "select status from workflow.wf_task where id='" + review.task().id() + "'"));
        assertEquals(1L, scalarLong(
                "select count(*) from workflow.wf_action_log where instance_id='" + review.instance().id()
                        + "' and action_code='APPROVE'"));
        assertEquals(1L, scalarLong("""
                select count(*) from core.idempotency_record
                where idempotency_key in ('c8-race-action-a','c8-race-action-b')
                """));
    }

    @Test
    void idempotencyReplayHashConflictAndReturnRejectWithdrawEvidenceStayDistinct() throws Exception {
        RuntimeSeed seed = seedRuntimeGraph();
        RuntimeHandle runtime = runtime();

        WorkflowRuntimeService.StartCommand original = startCommand(seed, "c8-idem-start", "Idempotent workflow");
        WorkflowRuntimeService.Result first = runtime.tx().execute(status -> runtime.service().start(original));
        WorkflowRuntimeService.Result replay = runtime.tx().execute(status -> runtime.service().start(original));
        assertNotNull(first);
        assertNotNull(replay);
        assertTrue(replay.replayed());
        assertEquals(first.instance().id(), replay.instance().id());
        WorkflowException changedStart = assertWorkflowFailure(() -> runtime.tx().execute(status -> runtime.service().start(
                startCommand(seed, "c8-idem-start", "Changed request must conflict"))));
        assertEquals(WorkflowException.Code.CONFLICT, changedStart.code());
        assertEquals(1L, scalarLong(
                "select count(*) from core.idempotency_record where idempotency_key='c8-idem-start'"));

        WorkflowRuntimeService.Result approveReview = runtime.tx().execute(status -> runtime.service().act(actionCommand(
                seed.actorId(), first.instance().id(), null, "START", "SUBMIT", "c8-idem-submit", "submit")));
        assertNotNull(approveReview);
        assign(approveReview.task().id(), seed.actorId());
        WorkflowRuntimeService.ActionCommand approve = actionCommand(
                seed.actorId(), approveReview.instance().id(), approveReview.task().id(), "REVIEW", "APPROVE",
                "c8-idem-approve", "approve");
        WorkflowRuntimeService.Result approved = runtime.tx().execute(status -> runtime.service().act(approve));
        WorkflowRuntimeService.Result approveReplay = runtime.tx().execute(status -> runtime.service().act(approve));
        assertNotNull(approved);
        assertNotNull(approveReplay);
        assertTrue(approveReplay.replayed());
        assertEquals(approved.action().id(), approveReplay.action().id());
        WorkflowException changedApprove = assertWorkflowFailure(() -> runtime.tx().execute(status -> runtime.service().act(
                actionCommand(seed.actorId(), approveReview.instance().id(), approveReview.task().id(), "REVIEW", "APPROVE",
                        "c8-idem-approve", "changed reason"))));
        assertEquals(WorkflowException.Code.CONFLICT, changedApprove.code());
        assertEquals(1L, scalarLong(
                "select count(*) from workflow.wf_action_log where instance_id='" + approveReview.instance().id()
                        + "' and action_code='APPROVE'"));

        WorkflowRuntimeService.Result rejectReview = toReview(runtime, seed, "c8-reject");
        assign(rejectReview.task().id(), seed.actorId());
        WorkflowRuntimeService.Result rejected = runtime.tx().execute(status -> runtime.service().act(actionCommand(
                seed.actorId(), rejectReview.instance().id(), rejectReview.task().id(), "REVIEW", "REJECT",
                "c8-reject-action", "reject reason")));
        assertEquals(WorkflowRuntimeService.REJECTED, rejected.instance().status());
        assertEquals("REJECT", rejected.action().actionCode());
        assertEquals("REJECT", scalarString(
                "select result_code from workflow.wf_task where id='" + rejectReview.task().id() + "'"));

        WorkflowRuntimeService.Result returnReview = toReview(runtime, seed, "c8-return");
        assign(returnReview.task().id(), seed.actorId());
        WorkflowRuntimeService.Result returned = runtime.tx().execute(status -> runtime.service().act(actionCommand(
                seed.actorId(), returnReview.instance().id(), returnReview.task().id(), "REVIEW", "RETURN",
                "c8-return-action", "return reason")));
        assertEquals(WorkflowRuntimeService.RUNNING, returned.instance().status());
        assertEquals("START", returned.instance().currentNodeCode());
        assertEquals("RETURN", returned.action().actionCode());

        WorkflowRuntimeService.Result withdrawStart = runtime.tx().execute(status -> runtime.service().start(startCommand(
                seed, "c8-withdraw-start", "Withdraw workflow")));
        WorkflowRuntimeService.Result withdrawn = runtime.tx().execute(status -> runtime.service().act(actionCommand(
                seed.actorId(), withdrawStart.instance().id(), null, "START", "WITHDRAW",
                "c8-withdraw-action", "withdraw reason")));
        assertEquals(WorkflowRuntimeService.WITHDRAWN, withdrawn.instance().status());
        assertEquals("WITHDRAW", withdrawn.action().actionCode());

        assertEquals(1L, scalarLong("select count(*) from workflow.wf_action_log where id='" + rejected.action().id()
                + "' and action_code='REJECT' and to_status='END_REJECT'"));
        assertEquals(1L, scalarLong("select count(*) from workflow.wf_action_log where id='" + returned.action().id()
                + "' and action_code='RETURN' and from_status='REVIEW' and to_status='START'"));
        assertEquals(1L, scalarLong("select count(*) from workflow.wf_action_log where id='" + withdrawn.action().id()
                + "' and action_code='WITHDRAW' and from_status='START' and to_status='END_WITHDRAW'"));
    }

    private static Attempt concurrentApprove(
            UUID actorId,
            UUID instanceId,
            UUID taskId,
            String key,
            CountDownLatch ready,
            CountDownLatch fire) {
        RuntimeHandle runtime = runtime();
        ready.countDown();
        try {
            if (!fire.await(20, TimeUnit.SECONDS)) return new Attempt(false, WorkflowException.Code.CONFLICT);
            runtime.tx().execute(status -> runtime.service().act(actionCommand(
                    actorId, instanceId, taskId, "REVIEW", "APPROVE", key, key)));
            return new Attempt(true, null);
        } catch (WorkflowException ex) {
            return new Attempt(false, ex.code());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt(false, WorkflowException.Code.CONFLICT);
        } catch (RuntimeException ex) {
            Throwable current = ex;
            while (current != null) {
                if (current instanceof WorkflowException workflow) return new Attempt(false, workflow.code());
                current = current.getCause();
            }
            throw ex;
        }
    }

    private static WorkflowRuntimeService.Result toReview(RuntimeHandle runtime, RuntimeSeed seed, String prefix) {
        WorkflowRuntimeService.Result started = runtime.tx().execute(status -> runtime.service().start(startCommand(
                seed, prefix + "-start", prefix + " workflow")));
        assertNotNull(started);
        WorkflowRuntimeService.Result review = runtime.tx().execute(status -> runtime.service().act(actionCommand(
                seed.actorId(), started.instance().id(), null, "START", "SUBMIT", prefix + "-submit", "submit")));
        assertNotNull(review);
        assertNotNull(review.task());
        return review;
    }

    private static RuntimeHandle runtime() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl("sjg_oms"), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WorkflowRuntimeService service = new WorkflowRuntimeService(
                new JdbcWorkflowRuntimeRepository(jdbc, new ObjectMapper()),
                new CoreWorkflowIdempotency(new IdempotencyRegistry(jdbc)),
                new FailClosedTransitionConditionEvaluator(),
                new ObjectMapper());
        return new RuntimeHandle(service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static RuntimeSeed seedRuntimeGraph() throws Exception {
        UUID actorId = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('"
                + actorId + "','" + BOOTSTRAP_TENANT + "','C8-" + shortId() + "','C8 Workflow Actor','ACTIVE')");
        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + BOOTSTRAP_TENANT
                + "' and process_code='P006'");
        UUID versionId = UUID.randomUUID();
        int versionNo = VERSION_SEQUENCE.incrementAndGet();
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + BOOTSTRAP_TENANT + "','" + definitionId + "'," + versionNo
                + ",'DRAFT','{}'::jsonb,'c8-draft-" + shortId() + "')");
        insertNode(versionId, "START", "START", 10);
        insertNode(versionId, "REVIEW", "TASK", 20);
        insertNode(versionId, "END_OK", "END", 30);
        insertNode(versionId, "END_REJECT", "END", 40);
        insertNode(versionId, "END_WITHDRAW", "END", 50);
        insertTransition(versionId, "START", "SUBMIT", "REVIEW", false);
        insertTransition(versionId, "START", "WITHDRAW", "END_WITHDRAW", true);
        insertTransition(versionId, "REVIEW", "APPROVE", "END_OK", false);
        insertTransition(versionId, "REVIEW", "REJECT", "END_REJECT", true);
        insertTransition(versionId, "REVIEW", "RETURN", "START", true);
        execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='c8-published-"
                + shortId() + "' where id='" + versionId + "'");
        return new RuntimeSeed(actorId, versionId);
    }

    private static void insertNode(UUID versionId, String code, String type, int sort) throws SQLException {
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + BOOTSTRAP_TENANT + "','" + versionId + "','" + code + "','" + code
                + "','" + type + "'," + sort + ")");
    }

    private static void insertTransition(
            UUID versionId, String from, String action, String to, boolean rollback) throws SQLException {
        execute("insert into workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,is_rollback) values ('"
                + UUID.randomUUID() + "','" + BOOTSTRAP_TENANT + "','" + versionId + "','" + from + "','" + action
                + "','" + to + "'," + rollback + ")");
    }

    private static WorkflowRuntimeService.StartCommand startCommand(RuntimeSeed seed, String key, String title) {
        return new WorkflowRuntimeService.StartCommand(
                BOOTSTRAP_TENANT, seed.actorId(), null, seed.versionId(), "C8_TEST", null, null,
                title, "NORMAL", new ObjectMapper().createObjectNode().put("c8", true), key);
    }

    private static WorkflowRuntimeService.ActionCommand actionCommand(
            UUID actorId, UUID instanceId, UUID taskId, String expectedNode, String action, String key, String reason) {
        return new WorkflowRuntimeService.ActionCommand(
                BOOTSTRAP_TENANT, actorId, null, instanceId, taskId, expectedNode, action, reason, key);
    }

    private static WorkflowException assertWorkflowFailure(ThrowingOperation operation) {
        RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WorkflowException workflow) return workflow;
            current = current.getCause();
        }
        throw failure;
    }

    private static void assign(UUID taskId, UUID actorId) throws SQLException {
        execute("update workflow.wf_task set assignee_id='" + actorId + "' where id='" + taskId + "'");
    }

    private static void seedRlsTenants() throws Exception {
        tenantADefinition = UUID.randomUUID();
        tenantBDefinition = UUID.randomUUID();
        execute("insert into core.tenant(id,tenant_code,tenant_name,status,timezone) values ('" + TENANT_A
                + "','C8_RLS_A','C8 RLS Tenant A','ACTIVE','Asia/Shanghai'),('" + TENANT_B
                + "','C8_RLS_B','C8 RLS Tenant B','ACTIVE','Asia/Shanghai')");
        execute("insert into workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table) values ('"
                + tenantADefinition + "','" + TENANT_A + "','C8RLSA','C8 RLS Tenant A','C8','workflow','generic_request'),('"
                + tenantBDefinition + "','" + TENANT_B + "','C8RLSB','C8 RLS Tenant B','C8','workflow','generic_request')");
    }

    private static void assertApprovedRls(String qualified) throws Exception {
        String[] parts = qualified.split("\\.");
        try (Connection connection = admin("sjg_oms"); PreparedStatement table = connection.prepareStatement("""
                select c.relrowsecurity,pg_get_userbyid(c.relowner)
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname=? and c.relname=? and c.relkind in ('r','p')
                """)) {
            table.setString(1, parts[0]);
            table.setString(2, parts[1]);
            try (ResultSet result = table.executeQuery()) {
                assertTrue(result.next(), () -> "missing canonical workflow table " + qualified);
                assertTrue(result.getBoolean(1), () -> "RLS disabled on " + qualified);
                assertEquals("sjg_owner", result.getString(2), () -> "unexpected owner on " + qualified);
            }
            try (PreparedStatement policies = connection.prepareStatement(
                    "select count(*) from pg_policies where schemaname=? and tablename=?")) {
                policies.setString(1, parts[0]);
                policies.setString(2, parts[1]);
                try (ResultSet result = policies.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getLong(1) >= 1, () -> "no tenant RLS policy on " + qualified);
                }
            }
        }
    }

    private static <T> T inApiTenant(UUID tenantId, Function<Connection, T> work) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD)) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                context.setString(1, tenantId.toString());
                context.executeQuery();
            }
            try {
                T value = work.apply(connection);
                connection.commit();
                return value;
            } catch (RuntimeException ex) {
                connection.rollback();
                if (ex instanceof DatabaseTestException database && database.getCause() instanceof SQLException sql) {
                    throw sql;
                }
                throw ex;
            }
        }
    }

    private static long count(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        } catch (SQLException ex) {
            throw new DatabaseTestException(ex);
        }
    }

    private static boolean hasTablePrivilege(Connection connection, String role, String table, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select has_table_privilege(?,?,?)")) {
            statement.setString(1, role);
            statement.setString(2, table);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean hasSchemaPrivilege(Connection connection, String role, String schema, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select has_schema_privilege(?,?,?)")) {
            statement.setString(1, role);
            statement.setString(2, schema);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID scalarUuid(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            UUID value = result.getObject(1, UUID.class);
            assertNotNull(value);
            return value;
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", BOOTSTRAP_TENANT.toString(),
                        "sjg_tenant_code", "PHASE05_C8",
                        "sjg_tenant_name", "PHASE-05 C8 Final Test"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String database) {
        String url = postgres.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String base = query >= 0 ? url.substring(0, query) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md"))
                    && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }

    private record RuntimeSeed(UUID actorId, UUID versionId) {}
    private record RuntimeHandle(WorkflowRuntimeService service, TransactionTemplate tx) {}
    private record Attempt(boolean success, WorkflowException.Code code) {}

    private static final class DatabaseTestException extends RuntimeException {
        DatabaseTestException(SQLException cause) {
            super(cause);
        }
    }
}
