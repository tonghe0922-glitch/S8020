package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.workflow.JdbcWorkflowSlaRepository;
import cn.shangjingu.platform.workflow.WorkflowSlaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase05WorkflowSlaDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AtomicInteger VERSION_SEQUENCE = new AtomicInteger(9600);
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-sla-test-only");
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = connection("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void slaPauseResumePreservesOriginalDueAndPersistsReminderEscalationEvidence() throws Exception {
        Seed seed = seedSlaTask();
        CapturingNotification notification = new CapturingNotification();
        Runtime runtime = runtime(seed.assigneeId(), notification);
        Instant receivedAt = seed.receivedAt();

        WorkflowSlaService.SlaState started = runtime.tx().execute(status -> runtime.service().start(
                TENANT_ID, seed.taskId(), seed.assigneeId(), "db-sla-start-" + seed.taskId(), receivedAt));
        assertNotNull(started);
        assertEquals(receivedAt.plus(Duration.ofMinutes(120)), started.originalDueAt());
        assertEquals(started.originalDueAt(), scalarInstant("select due_at from workflow.wf_task where id='" + seed.taskId() + "'"));

        Instant pauseAt = receivedAt.plus(Duration.ofMinutes(30));
        runtime.tx().execute(status -> runtime.service().pause(
                TENANT_ID, seed.taskId(), seed.assigneeId(), "db-sla-pause-" + seed.taskId(), pauseAt));
        Instant resumeAt = pauseAt.plus(Duration.ofMinutes(45));
        WorkflowSlaService.SlaState resumed = runtime.tx().execute(status -> runtime.service().resume(
                TENANT_ID, seed.taskId(), seed.assigneeId(), "db-sla-resume-" + seed.taskId(), resumeAt));
        assertNotNull(resumed);
        assertEquals(started.originalDueAt(), resumed.originalDueAt());
        assertEquals(receivedAt.plus(Duration.ofMinutes(165)), resumed.effectiveDueAt());
        assertEquals(resumed.effectiveDueAt(), scalarInstant("select due_at from workflow.wf_task where id='" + seed.taskId() + "'"));
        assertEquals(resumed.effectiveDueAt(), scalarInstant("select due_at from workflow.wf_instance where id='" + seed.instanceId() + "'"));

        JsonNode pauseEvidence = actionReason(seed.taskId(), WorkflowSlaService.SLA_PAUSED);
        JsonNode resumeEvidence = actionReason(seed.taskId(), WorkflowSlaService.SLA_RESUMED);
        assertEquals(started.originalDueAt().toString(), pauseEvidence.get("originalDueAt").asText());
        assertEquals(started.originalDueAt().toString(), resumeEvidence.get("originalDueAt").asText());
        assertEquals(45, resumeEvidence.get("pausedWorkingMinutes").asLong());

        List<WorkflowSlaService.PendingNotification> due = runtime.tx().execute(status -> runtime.service().evaluate(
                TENANT_ID, seed.taskId(), seed.assigneeId(), resumed.effectiveDueAt().plusSeconds(60)));
        assertNotNull(due);
        assertEquals(2, due.size());
        assertEquals(1, actionCount(seed.taskId(), WorkflowSlaService.SLA_REMINDER_DUE));
        assertEquals(1, actionCount(seed.taskId(), WorkflowSlaService.SLA_ESCALATION_DUE));

        for (WorkflowSlaService.PendingNotification event : due) {
            runtime.tx().execute(status -> runtime.service().dispatch(event, seed.assigneeId(), resumed.effectiveDueAt().plusSeconds(120)));
        }
        assertEquals(2, notification.deliveries);
        assertEquals(1, actionCount(seed.taskId(), WorkflowSlaService.SLA_REMINDER_SENT));
        assertEquals(1, actionCount(seed.taskId(), WorkflowSlaService.SLA_ESCALATION_SENT));

        List<WorkflowSlaService.PendingNotification> duplicate = runtime.tx().execute(status -> runtime.service().evaluate(
                TENANT_ID, seed.taskId(), seed.assigneeId(), resumed.effectiveDueAt().plusSeconds(180)));
        assertNotNull(duplicate);
        assertTrue(duplicate.isEmpty());
    }

    @Test
    void pausedSlaSuppressesDueDecisions() throws Exception {
        Seed seed = seedSlaTask();
        Runtime runtime = runtime(seed.assigneeId(), new CapturingNotification());
        runtime.tx().execute(status -> runtime.service().start(
                TENANT_ID, seed.taskId(), seed.assigneeId(), "db-pause-start-" + seed.taskId(), seed.receivedAt()));
        runtime.tx().execute(status -> runtime.service().pause(
                TENANT_ID, seed.taskId(), seed.assigneeId(), "db-pause-only-" + seed.taskId(), seed.receivedAt().plusSeconds(60)));
        List<WorkflowSlaService.PendingNotification> due = runtime.tx().execute(status -> runtime.service().evaluate(
                TENANT_ID, seed.taskId(), seed.assigneeId(), seed.receivedAt().plus(Duration.ofHours(5))));
        assertNotNull(due);
        assertTrue(due.isEmpty());
    }

    private static Runtime runtime(UUID recipient, CapturingNotification notification) {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        var repository = new JdbcWorkflowSlaRepository(jdbc, mapper);
        var calendar = new ContinuousCalendar();
        var evaluator = new FixedEvaluator(recipient);
        var service = new WorkflowSlaService(
                repository, mapper, List.of(calendar), List.of(evaluator), List.of(notification), List.of());
        return new Runtime(service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static Seed seedSlaTask() throws Exception {
        UUID assigneeId = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('"
                + assigneeId + "','" + TENANT_ID + "','SLA-" + shortId() + "','SLA Approver','ACTIVE')");

        UUID policyId = UUID.randomUUID();
        execute("insert into workflow.wf_sla_policy(id,tenant_id,policy_code,process_code,node_code,duration_minutes,remind_rules,escalation_rules) values ('"
                + policyId + "','" + TENANT_ID + "','SLA-" + shortId() + "','P004','REVIEW',120,'{\"opaque\":\"remind\"}'::jsonb,'{\"opaque\":\"escalate\"}'::jsonb)");

        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P004'");
        UUID versionId = UUID.randomUUID();
        int versionNo = VERSION_SEQUENCE.incrementAndGet();
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "'," + versionNo + ",'DRAFT','{}'::jsonb,'sla-draft-" + shortId() + "')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sla_policy_id,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','REVIEW','Review','TASK','" + policyId + "',20)");
        execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='sla-published-" + shortId()
                + "' where id='" + versionId + "'");

        Instant receivedAt = Instant.parse("2026-08-08T00:00:00Z");
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        execute("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,title,initiator_id,current_node_code,status,priority,started_at,context_snapshot) values ('"
                + instanceId + "','" + TENANT_ID + "','WFI-SLA-" + shortId() + "','" + definitionId + "','" + versionId
                + "','P004','TEST','SLA runtime','" + assigneeId + "','REVIEW','RUNNING','NORMAL','" + receivedAt + "','{}'::jsonb)");
        execute("insert into workflow.wf_task(id,tenant_id,instance_id,task_no,node_code,task_type,assignee_id,status,received_at) values ('"
                + taskId + "','" + TENANT_ID + "','" + instanceId + "','WFT-SLA-" + shortId()
                + "','REVIEW','APPROVAL','" + assigneeId + "','PENDING','" + receivedAt + "')");
        return new Seed(taskId, instanceId, assigneeId, receivedAt);
    }

    private static JsonNode actionReason(UUID taskId, String actionCode) throws Exception {
        String json = scalarString("select reason from workflow.wf_action_log where task_id='" + taskId
                + "' and action_code='" + actionCode + "' order by occurred_at,id limit 1");
        return new ObjectMapper().readTree(json);
    }

    private static int actionCount(UUID taskId, String actionCode) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select count(*) from workflow.wf_action_log where task_id='" + taskId + "' and action_code='" + actionCode + "'")) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private static Instant scalarInstant(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getTimestamp(1).toInstant();
        }
    }

    private static UUID scalarUuid(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            UUID value = r.getObject(1, UUID.class);
            assertNotNull(value);
            return value;
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        var locations = new java.util.ArrayList<String>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_ID.toString(),
                        "sjg_tenant_code", "PHASE05_SLA",
                        "sjg_tenant_name", "PHASE-05 SLA Test"))
                .cleanDisabled(true).load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl("sjg_oms"), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection connection(String database) throws SQLException {
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
            if (Files.isRegularFile(current.resolve("AGENT.md")) && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    private record Seed(UUID taskId, UUID instanceId, UUID assigneeId, Instant receivedAt) {}
    private record Runtime(WorkflowSlaService service, TransactionTemplate tx) {}

    private static final class ContinuousCalendar implements WorkflowSlaService.WorkingCalendarCapability {
        @Override public boolean supports(UUID calendarId) { return calendarId == null; }
        @Override public Instant addWorkingMinutes(UUID tenantId, UUID calendarId, Instant start, long workingMinutes) {
            return start.plus(Duration.ofMinutes(workingMinutes));
        }
        @Override public long workingMinutesBetween(UUID tenantId, UUID calendarId, Instant start, Instant end) {
            return Duration.between(start, end).toMinutes();
        }
    }

    private static final class FixedEvaluator implements WorkflowSlaService.RuleEvaluatorCapability {
        private final UUID recipient;
        private FixedEvaluator(UUID recipient) { this.recipient = recipient; }
        @Override public boolean supports(WorkflowSlaService.SlaPolicy policy) { return true; }
        @Override public List<WorkflowSlaService.Decision> evaluate(
                UUID tenantId, WorkflowSlaService.SlaPolicy policy, WorkflowSlaService.SlaInstance instance,
                WorkflowSlaService.SlaTask task, Instant now) {
            return List.of(
                    new WorkflowSlaService.Decision(WorkflowSlaService.DecisionKind.REMINDER, "R1", List.of(recipient)),
                    new WorkflowSlaService.Decision(WorkflowSlaService.DecisionKind.ESCALATION, "E1", List.of(recipient)));
        }
    }

    private static final class CapturingNotification implements WorkflowSlaService.NotificationCapability {
        int deliveries;
        @Override public boolean supports(WorkflowSlaService.DecisionKind kind) { return true; }
        @Override public WorkflowSlaService.DeliveryReceipt deliver(WorkflowSlaService.PendingNotification notification) {
            deliveries++;
            return new WorkflowSlaService.DeliveryReceipt("db-receipt-" + deliveries);
        }
    }
}
