package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.workflow.CoreWorkflowOrchestrationNumberCapability;
import cn.shangjingu.platform.workflow.JdbcWorkflowOrchestrationRepository;
import cn.shangjingu.platform.workflow.WorkflowException;
import cn.shangjingu.platform.workflow.WorkflowOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
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

class Phase05WorkflowOrchestrationDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AtomicInteger VERSION_SEQUENCE = new AtomicInteger(9800);
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-orchestration-test-only");
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = connection("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        execute("insert into core.sequence_rule(id,tenant_id,rule_code,prefix_template,current_value,step) "
                + "select '" + UUID.randomUUID() + "','" + TENANT_ID + "','P120','P120-',1000,1 "
                + "where not exists (select 1 from core.sequence_rule where tenant_id='" + TENANT_ID + "' and rule_code='P120' and not is_deleted)");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void createsItemsLinksProgressAndCloseWithRealOptimisticVersions() throws Exception {
        Seed seed = seed();
        Runtime runtime = runtime();
        WorkflowOrchestrationService.PhysicalSourceFields source = source(seed.leadCenterId());

        WorkflowOrchestrationService.Orchestration created = runtime.tx().execute(status -> runtime.service().create(
                TENANT_ID, seed.actorId(), new WorkflowOrchestrationService.CreateCommand("P120", null, source)));
        assertNotNull(created);
        assertEquals("DRAFT", created.status());
        assertTrue(created.businessNo().startsWith("P120-"));
        assertEquals(0, created.versionNo());
        assertEquals(0, created.masterChangeVersion());

        WorkflowOrchestrationService.Orchestration withItem = runtime.tx().execute(status -> runtime.service().addItem(
                TENANT_ID, seed.actorId(), created.id(), 0,
                new WorkflowOrchestrationService.ItemCommand(
                        "child_orders_tasks", 0, "CHILD-1", "Child task", "OPEN", null,
                        null, "workflow_instance", seed.childInstanceId(), null, null, 10)));
        assertNotNull(withItem);
        assertEquals(1, withItem.versionNo());
        assertEquals(1, withItem.masterChangeVersion());
        assertEquals(1, scalarInt("select count(*) from workflow.wf_orchestration_instance_item where master_id='" + created.id() + "'"));

        WorkflowOrchestrationService.Orchestration withLink = runtime.tx().execute(status -> runtime.service().addLink(
                TENANT_ID, seed.actorId(), created.id(), 1,
                new WorkflowOrchestrationService.LinkCommand(
                        "P004", seed.childInstanceId(), "SOURCE_DEFINED", "M1", "ACTIVE", true)));
        assertNotNull(withLink);
        assertEquals(2, withLink.versionNo());
        assertEquals(1, scalarInt("select count(*) from workflow.wf_orchestration_link where orchestration_id='" + created.id() + "'"));

        WorkflowException stale = assertThrows(WorkflowException.class, () -> runtime.tx().execute(status -> runtime.service().updateProgress(
                TENANT_ID, seed.actorId(), created.id(), 1, "STALE", new BigDecimal("0.2"))));
        assertEquals(WorkflowException.Code.STALE_VERSION, stale.code());

        WorkflowOrchestrationService.Orchestration progressed = runtime.tx().execute(status -> runtime.service().updateProgress(
                TENANT_ID, seed.actorId(), created.id(), 2, "M2", new BigDecimal("0.5000")));
        assertNotNull(progressed);
        assertEquals(3, progressed.versionNo());
        assertEquals(new BigDecimal("0.5000"), progressed.completionRate());

        WorkflowOrchestrationService.Orchestration active = runtime.tx().execute(status -> runtime.service().changeStatus(
                TENANT_ID, seed.actorId(), created.id(), 3, "ACTIVE"));
        assertNotNull(active);
        assertEquals(4, active.versionNo());
        assertEquals("ACTIVE", active.status());

        Instant closedAt = source.actualStartAt().plusSeconds(3600);
        WorkflowOrchestrationService.Orchestration closed = runtime.tx().execute(status -> runtime.service().close(
                TENANT_ID, seed.actorId(), created.id(), 4, closedAt));
        assertNotNull(closed);
        assertEquals(5, closed.versionNo());
        assertEquals(5, closed.masterChangeVersion());
        assertEquals("CLOSED", closed.status());
        assertEquals(closedAt, scalarInstant("select actual_end_at from workflow.wf_orchestration_instance where id='" + created.id() + "'"));
    }

    @Test
    void childProcessMismatchAndStaleDuplicateMutationFailClosed() throws Exception {
        Seed seed = seed();
        Runtime runtime = runtime();
        WorkflowOrchestrationService.Orchestration created = runtime.tx().execute(status -> runtime.service().create(
                TENANT_ID, seed.actorId(), new WorkflowOrchestrationService.CreateCommand("P120", null, source(seed.leadCenterId()))));
        assertNotNull(created);

        WorkflowException mismatch = assertThrows(WorkflowException.class, () -> runtime.tx().execute(status -> runtime.service().addLink(
                TENANT_ID, seed.actorId(), created.id(), 0,
                new WorkflowOrchestrationService.LinkCommand("P005", seed.childInstanceId(), "SOURCE_DEFINED", null, "ACTIVE", true))));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, mismatch.code());
        assertEquals(0, scalarInt("select version_no from workflow.wf_orchestration_instance where id='" + created.id() + "'"));

        WorkflowOrchestrationService.Orchestration firstItem = runtime.tx().execute(status -> runtime.service().addItem(
                TENANT_ID, seed.actorId(), created.id(), 0,
                new WorkflowOrchestrationService.ItemCommand(
                        "critical_dependencies", 0, "DEP-1", "Dependency", null, null,
                        new ObjectMapper().createObjectNode().put("source", "approved-field"), null, null, null, null, 0)));
        assertNotNull(firstItem);
        WorkflowException stale = assertThrows(WorkflowException.class, () -> runtime.tx().execute(status -> runtime.service().addItem(
                TENANT_ID, seed.actorId(), created.id(), 0,
                new WorkflowOrchestrationService.ItemCommand(
                        "critical_dependencies", 1, "DEP-2", "Stale dependency", "X", null,
                        null, null, null, null, null, 1))));
        assertEquals(WorkflowException.Code.STALE_VERSION, stale.code());
        assertEquals(1, scalarInt("select count(*) from workflow.wf_orchestration_instance_item where master_id='" + created.id() + "'"));
    }

    private static Runtime runtime() {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = new JdbcWorkflowOrchestrationRepository(jdbc, new ObjectMapper());
        var numberCapability = new CoreWorkflowOrchestrationNumberCapability(new BusinessNumberService(jdbc));
        var service = new WorkflowOrchestrationService(repository, numberCapability, List.of(new TestStateProvider()));
        return new Runtime(service, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static Seed seed() throws Exception {
        UUID actorId = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('"
                + actorId + "','" + TENANT_ID + "','ORCH-" + shortId() + "','Orchestration Actor','ACTIVE')");
        UUID leadCenterId = UUID.randomUUID();
        execute("insert into org.organization(id,tenant_id,org_code,org_name,org_type,status) values ('"
                + leadCenterId + "','" + TENANT_ID + "','CENTER-" + shortId() + "','Lead Center','CENTER','ACTIVE')");

        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P004'");
        UUID versionId = UUID.randomUUID();
        int versionNo = VERSION_SEQUENCE.incrementAndGet();
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "'," + versionNo
                + ",'DRAFT','{}'::jsonb,'orchestration-child-draft-" + shortId() + "')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='orchestration-child-published-"
                + shortId() + "' where id='" + versionId + "'");
        UUID childInstanceId = UUID.randomUUID();
        execute("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,title,initiator_id,current_node_code,status,priority,context_snapshot) values ('"
                + childInstanceId + "','" + TENANT_ID + "','WFI-ORCH-" + shortId() + "','" + definitionId + "','" + versionId
                + "','P004','TEST','Child workflow','" + actorId + "','START','RUNNING','NORMAL','{}'::jsonb)");
        return new Seed(actorId, leadCenterId, childInstanceId);
    }

    private static WorkflowOrchestrationService.PhysicalSourceFields source(UUID leadCenterId) {
        ObjectMapper mapper = new ObjectMapper();
        return new WorkflowOrchestrationService.PhysicalSourceFields(
                "MASTER-001", leadCenterId, mapper.createArrayNode().add(leadCenterId.toString()), "M0",
                mapper.createArrayNode().add("SOURCE-CRITICAL"), mapper.createObjectNode().put("source", "RACI"),
                new BigDecimal("123.45"), null, Instant.parse("2026-08-08T00:00:00Z"), LocalDate.of(2026, 8, 8),
                "Contact", "CONTENT-001", "Content title", "SOURCE_TYPE", "CUST-001", "Customer",
                "Guest team", "AREA-001", "PATROL-001", "SOURCE_INCIDENT", "ASSET-001", "Asset",
                "Person", "PERSON-001", "PROGRAM-001", "LEVEL-1", "TEAM-001", "Source result",
                "SESSION-001", Instant.parse("2026-08-08T01:00:00Z"), "MODEL-001", "JOB-001");
    }

    private static final class TestStateProvider implements WorkflowOrchestrationService.StateTransitionCapability {
        @Override public boolean supports(String processCode) { return "P120".equals(processCode); }
        @Override public void requireAllowed(String processCode, String currentStatus, String targetStatus, boolean closing) {
            if (closing) {
                if (!"ACTIVE".equals(currentStatus) || !"CLOSED".equals(targetStatus)) {
                    throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION, "test close transition rejected");
                }
            } else if (!"DRAFT".equals(currentStatus) || !"ACTIVE".equals(targetStatus)) {
                throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION, "test transition rejected");
            }
        }
        @Override public String closingStatus(String processCode, String currentStatus) { return "CLOSED"; }
    }

    private static int scalarInt(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getInt(1);
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

    private static Instant scalarInstant(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getTimestamp(1).toInstant();
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
                        "sjg_tenant_code", "PHASE05_ORCH",
                        "sjg_tenant_name", "PHASE-05 Orchestration Test"))
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

    private record Seed(UUID actorId, UUID leadCenterId, UUID childInstanceId) {}
    private record Runtime(WorkflowOrchestrationService service, TransactionTemplate tx) {}
}
