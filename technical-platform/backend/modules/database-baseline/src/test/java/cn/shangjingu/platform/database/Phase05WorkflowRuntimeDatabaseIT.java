package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase05WorkflowRuntimeDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void migrateDatabase() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-runtime-test-only");
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
    void instanceTaskAndActionMustStayInsidePublishedBoundVersion() throws Exception {
        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P003'");
        UUID employeeId = createEmployee();
        UUID publishedVersion = createVersion(definitionId, 9101, "PUBLISHED");
        UUID secondPublished = createVersion(definitionId, 9102, "PUBLISHED");
        UUID draftVersion = createVersion(definitionId, 9103, "DRAFT");
        UUID instanceId = UUID.randomUUID();

        execute("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,"
                + "business_object_type,title,initiator_id,current_node_code,status,priority,context_snapshot) values ('"
                + instanceId + "','" + TENANT_ID + "','WFI-RUNTIME-1','" + definitionId + "','" + publishedVersion
                + "','P003','TEST','Runtime test','" + employeeId + "','START','RUNNING','NORMAL','{}'::jsonb)");

        assertSqlRejected("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,"
                + "business_object_type,title,initiator_id,current_node_code,status,priority) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','WFI-DRAFT','" + definitionId + "','" + draftVersion
                + "','P003','TEST','Draft invalid','" + employeeId + "','START','RUNNING','NORMAL')");
        assertSqlRejected("update workflow.wf_instance set version_id='" + secondPublished + "' where id='" + instanceId + "'");
        assertSqlRejected("update workflow.wf_instance set current_node_code='OUTSIDE' where id='" + instanceId + "'");

        UUID taskId = UUID.randomUUID();
        execute("insert into workflow.wf_task(id,tenant_id,instance_id,task_no,node_code,task_type,status) values ('"
                + taskId + "','" + TENANT_ID + "','" + instanceId + "','WFT-RUNTIME-1','REVIEW','TASK','PENDING')");
        assertSqlRejected("insert into workflow.wf_task(id,tenant_id,instance_id,task_no,node_code,task_type,status) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + instanceId + "','WFT-INVALID','OUTSIDE','TASK','PENDING')");
        assertSqlRejected("update workflow.wf_task set node_code='END' where id='" + taskId + "'");

        UUID actionId = UUID.randomUUID();
        execute("insert into workflow.wf_action_log(id,tenant_id,instance_id,task_id,action_code,from_status,to_status,request_id) values ('"
                + actionId + "','" + TENANT_ID + "','" + instanceId + "','" + taskId
                + "','APPROVE','REVIEW','END','runtime-request-1')");
        assertSqlRejected("insert into workflow.wf_action_log(id,tenant_id,instance_id,action_code,request_id) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + instanceId + "','APPROVE','runtime-request-1')");
        assertSqlRejected("insert into workflow.wf_action_log(id,tenant_id,instance_id,task_id,action_code,request_id) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + UUID.randomUUID() + "','" + taskId
                + "','APPROVE','runtime-request-2')");

        assertEquals(publishedVersion, scalarUuid("select version_id from workflow.wf_instance where id='" + instanceId + "'"));
        assertEquals("START", scalarString("select current_node_code from workflow.wf_instance where id='" + instanceId + "'"));
        assertEquals(1L, scalarLong("select count(*) from workflow.wf_action_log where request_id='runtime-request-1'"));
    }

    private static UUID createEmployee() throws SQLException {
        UUID employeeId = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('"
                + employeeId + "','" + TENANT_ID + "','PHASE05-RUNTIME-EMP','PHASE05 Runtime Employee','ACTIVE')");
        return employeeId;
    }

    private static UUID createVersion(UUID definitionId, int versionNo, String status) throws SQLException {
        UUID versionId = UUID.randomUUID();
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "'," + versionNo + ",'DRAFT',null,'{}'::jsonb,'draft-"
                + versionNo + "')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','REVIEW','Review','TASK',20)");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','END','End','END',30)");
        if ("PUBLISHED".equals(status)) {
            execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='published-" + versionNo
                    + "' where id='" + versionId + "'");
        }
        return versionId;
    }

    private static void assertSqlRejected(String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
        assertTrue(failure.getSQLState() != null && !failure.getSQLState().isBlank());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID scalarUuid(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getObject(1, UUID.class);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        var locations = new java.util.ArrayList<String>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_ID.toString(),
                        "sjg_tenant_code", "PHASE05_RUNTIME",
                        "sjg_tenant_name", "PHASE-05 Runtime Test"))
                .cleanDisabled(true)
                .load();
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
            if (Files.isRegularFile(current.resolve("AGENT.md"))
                    && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
