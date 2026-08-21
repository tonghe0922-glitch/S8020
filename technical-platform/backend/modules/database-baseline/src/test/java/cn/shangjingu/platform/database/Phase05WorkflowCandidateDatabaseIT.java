package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.org.infrastructure.JdbcOrgDirectoryAdapter;
import cn.shangjingu.platform.workflow.JdbcWorkflowTaskAssignmentRepository;
import cn.shangjingu.platform.workflow.WorkflowCandidateResolver;
import cn.shangjingu.platform.workflow.WorkflowException;
import cn.shangjingu.platform.workflow.WorkflowTaskAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

class Phase05WorkflowCandidateDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AtomicInteger VERSION_SEQUENCE = new AtomicInteger(9400);
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-candidate-test-only");
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
    void orgPositionCandidatesClaimTaskAndCompetingClaimCannotOverwrite() throws Exception {
        Seed seed = seedCandidateTask(false);
        AssignmentRuntime runtime = assignmentRuntime();

        WorkflowTaskAssignmentService.ClaimResult claimed = runtime.tx().execute(status -> runtime.service().claim(
                new WorkflowTaskAssignmentService.ClaimCommand(TENANT_ID, seed.taskId(), seed.approverA())));
        assertNotNull(claimed);
        assertEquals(seed.approverA(), claimed.assigneeId());
        assertTrue(claimed.eligibleCandidateIds().contains(seed.approverA()));
        assertTrue(claimed.eligibleCandidateIds().contains(seed.approverB()));
        assertTrue(!claimed.eligibleCandidateIds().contains(seed.initiator()));
        assertEquals(seed.approverA(), scalarUuid("select assignee_id from workflow.wf_task where id='" + seed.taskId() + "'"));

        WorkflowException competing = assertThrows(WorkflowException.class, () -> runtime.tx().execute(status -> runtime.service().claim(
                new WorkflowTaskAssignmentService.ClaimCommand(TENANT_ID, seed.taskId(), seed.approverB()))));
        assertEquals(WorkflowException.Code.STALE_VERSION, competing.code());
        assertEquals(seed.approverA(), scalarUuid("select assignee_id from workflow.wf_task where id='" + seed.taskId() + "'"));
    }

    @Test
    void dynamicRecusalCanRemoveLastEligibleApproverAndFailsClosed() throws Exception {
        Seed seed = seedCandidateTask(true);
        AssignmentRuntime runtime = assignmentRuntime();

        WorkflowException noApprover = assertThrows(WorkflowException.class, () -> runtime.tx().execute(status -> runtime.service().claim(
                new WorkflowTaskAssignmentService.ClaimCommand(TENANT_ID, seed.taskId(), seed.approverA()))));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, noApprover.code());
        assertEquals(null, scalarNullableUuid("select assignee_id from workflow.wf_task where id='" + seed.taskId() + "'"));
    }

    private static AssignmentRuntime assignmentRuntime() {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        var directory = new JdbcOrgDirectoryAdapter(jdbc);
        var resolver = new WorkflowCandidateResolver(directory);
        var repository = new JdbcWorkflowTaskAssignmentRepository(jdbc, mapper);
        var service = new WorkflowTaskAssignmentService(repository, resolver);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new AssignmentRuntime(service, tx);
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl("sjg_oms"), postgres.getUsername(), postgres.getPassword());
    }

    private static Seed seedCandidateTask(boolean recuseOnlyApprover) throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        execute("insert into org.organization(id,tenant_id,org_code,org_name,org_type,status) values ('"
                + orgId + "','" + TENANT_ID + "','ORG-" + shortId() + "','Candidate Org','CENTER','ACTIVE')");
        execute("insert into org.position(id,tenant_id,position_code,position_name,org_id,status) values ('"
                + positionId + "','" + TENANT_ID + "','POS-" + shortId() + "','Approver','" + orgId + "','ACTIVE')");

        UUID initiator = createEmployee("INIT-" + shortId());
        UUID approverA = createEmployee("APP-A-" + shortId());
        UUID approverB = recuseOnlyApprover ? approverA : createEmployee("APP-B-" + shortId());
        execute("update org.employee set primary_org_id='" + orgId + "',primary_position_id='" + positionId
                + "' where id in ('" + initiator + "','" + approverA + "'"
                + (approverB.equals(approverA) ? "" : ",'" + approverB + "'") + ")");
        appoint(initiator, orgId, positionId);
        appoint(approverA, orgId, positionId);
        if (!approverB.equals(approverA)) appoint(approverB, orgId, positionId);

        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P004'");
        UUID versionId = createPublishedVersion(definitionId, orgId, positionId);
        UUID instanceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String context = recuseOnlyApprover
                ? "{\"amount\":500,\"riskLevel\":\"HIGH\",\"recusedEmployeeIds\":[\"" + approverA + "\"]}"
                : "{\"amount\":500,\"riskLevel\":\"HIGH\"}";
        execute("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,title,initiator_id,current_node_code,status,priority,context_snapshot) values ('"
                + instanceId + "','" + TENANT_ID + "','WFI-CAND-" + shortId() + "','" + definitionId + "','" + versionId
                + "','P004','TEST','Candidate runtime','" + initiator + "','REVIEW','RUNNING','NORMAL','" + context.replace("'", "''") + "'::jsonb)");
        String rule = candidateRule(orgId, positionId);
        execute("insert into workflow.wf_task(id,tenant_id,instance_id,task_no,node_code,task_type,candidate_rule,status,received_at) values ('"
                + taskId + "','" + TENANT_ID + "','" + instanceId + "','WFT-CAND-" + shortId()
                + "','REVIEW','APPROVAL','" + rule.replace("'", "''") + "'::jsonb,'PENDING',now())");
        return new Seed(taskId, initiator, approverA, approverB);
    }

    private static UUID createEmployee(String employeeNo) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('"
                + id + "','" + TENANT_ID + "','" + employeeNo + "','Candidate Employee','ACTIVE')");
        return id;
    }

    private static void appoint(UUID employeeId, UUID orgId, UUID positionId) throws SQLException {
        execute("insert into org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + employeeId + "','" + positionId + "','" + orgId
                + "',false,current_date - 1,'ACTIVE')");
    }

    private static UUID createPublishedVersion(UUID definitionId, UUID orgId, UUID positionId) throws SQLException {
        UUID versionId = UUID.randomUUID();
        int versionNo = VERSION_SEQUENCE.incrementAndGet();
        String rule = candidateRule(orgId, positionId);
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "'," + versionNo + ",'DRAFT','{}'::jsonb,'draft-candidate-" + shortId() + "')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','REVIEW','Review','TASK','"
                + rule.replace("'", "''") + "'::jsonb,20)");
        execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='published-candidate-" + shortId()
                + "' where id='" + versionId + "'");
        return versionId;
    }

    private static String candidateRule(UUID orgId, UUID positionId) {
        return "{\"resolver\":\"ORG_POSITION\",\"orgId\":\"" + orgId + "\",\"positionId\":\"" + positionId
                + "\",\"amount\":{\"minInclusive\":100,\"maxInclusive\":1000},\"riskLevels\":[\"HIGH\"]}";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID scalarUuid(String sql) throws SQLException {
        UUID value = scalarNullableUuid(sql);
        assertNotNull(value);
        return value;
    }

    private static UUID scalarNullableUuid(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getObject(1, UUID.class);
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
                        "sjg_tenant_code", "PHASE05_CANDIDATE",
                        "sjg_tenant_name", "PHASE-05 Candidate Test"))
                .cleanDisabled(true).load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
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

    private record AssignmentRuntime(WorkflowTaskAssignmentService service, TransactionTemplate tx) {}
    private record Seed(UUID taskId, UUID initiator, UUID approverA, UUID approverB) {}
}
