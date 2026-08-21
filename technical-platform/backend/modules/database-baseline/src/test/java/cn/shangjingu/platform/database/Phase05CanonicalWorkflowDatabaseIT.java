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

class Phase05CanonicalWorkflowDatabaseIT {
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
                .withPassword("phase05-canonical-test-only");
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
    void publishedVersionAndGraphAreImmutableInPostgres() throws Exception {
        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P001'");
        UUID versionId = UUID.randomUUID();
        UUID startNodeId = UUID.randomUUID();
        UUID endNodeId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();

        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "',9001,'DRAFT','{}'::jsonb,'draft-checksum')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + startNodeId + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + endNodeId + "','" + TENANT_ID + "','" + versionId + "','DONE','Done','END',20)");
        execute("insert into workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,is_rollback) values ('"
                + transitionId + "','" + TENANT_ID + "','" + versionId + "','START','SUBMIT','DONE',false)");
        execute("update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='published-checksum' where id='"
                + versionId + "'");

        assertSqlRejected("update workflow.wf_version set checksum='tampered' where id='" + versionId + "'");
        assertSqlRejected("delete from workflow.wf_version where id='" + versionId + "'");
        assertSqlRejected("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','LATE','Late','TASK',30)");
        assertSqlRejected("update workflow.wf_transition set action_code='ALTERED' where id='" + transitionId + "'");
        assertSqlRejected("delete from workflow.wf_node where id='" + startNodeId + "'");

        assertEquals("PUBLISHED", scalarString("select status from workflow.wf_version where id='" + versionId + "'"));
        assertEquals("published-checksum", scalarString("select checksum from workflow.wf_version where id='" + versionId + "'"));
        assertEquals(2L, scalarLong("select count(*) from workflow.wf_node where version_id='" + versionId + "'"));
    }

    @Test
    void definitionVersionAndNodeIdentityAreUniquePerTenant() throws Exception {
        UUID definitionId = scalarUuid("select id from workflow.wf_definition where tenant_id='" + TENANT_ID
                + "' and process_code='P002'");
        UUID versionId = UUID.randomUUID();
        execute("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + versionId + "','" + TENANT_ID + "','" + definitionId + "',9002,'DRAFT','{}'::jsonb,'draft-checksum')");
        assertSqlRejected("insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + definitionId + "',9002,'DRAFT','{}'::jsonb,'other-checksum')");

        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','CHECK','Check','TASK',10)");
        assertSqlRejected("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','CHECK','Duplicate','TASK',20)");
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

    private static UUID scalarUuid(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getObject(1, UUID.class);
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
                        "sjg_tenant_code", "PHASE05_CANONICAL",
                        "sjg_tenant_name", "PHASE-05 Canonical Workflow Test"))
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
