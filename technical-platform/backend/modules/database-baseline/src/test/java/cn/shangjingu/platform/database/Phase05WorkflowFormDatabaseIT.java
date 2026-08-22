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

class Phase05WorkflowFormDatabaseIT {
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
                .withPassword("phase05-form-test-only");
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = connection("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void publishedFormSubmissionAndFieldValuesPreserveExactVersionEvidence() throws Exception {
        UUID employeeId = createEmployee();
        UUID definitionId = scalarUuid(
                "select id from workflow.wf_definition where tenant_id='" + TENANT_ID + "' and process_code='P004'");
        UUID versionId = createPublishedVersion(definitionId, 9201);
        UUID instanceId = createInstance(definitionId, versionId, employeeId);
        UUID formId = UUID.randomUUID();

        execute(
                "insert into workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,enabled) values ('"
                        + formId + "','" + TENANT_ID
                        + "','PHASE05_FORM','Canonical Form','P004','START',1,'{\"fields\":[\"name\"]}'::jsonb,false)");
        assertSqlRejected(
                "insert into workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,enabled) values ('"
                        + UUID.randomUUID() + "','" + TENANT_ID
                        + "','PHASE05_FORM','Duplicate','P004','START',1,'{}'::jsonb,false)");
        execute("update workflow.wf_form_definition set enabled=true where id='" + formId + "'");
        assertSqlRejected("update workflow.wf_form_definition set form_name='Tampered' where id='" + formId + "'");
        assertSqlRejected("delete from workflow.wf_form_definition where id='" + formId + "'");

        UUID draftFormId = UUID.randomUUID();
        execute(
                "insert into workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,enabled) values ('"
                        + draftFormId + "','" + TENANT_ID
                        + "','PHASE05_FORM','Draft v2','P004','START',2,'{}'::jsonb,false)");
        assertSqlRejected(submissionSql(UUID.randomUUID(), "WFS-DRAFT", instanceId, draftFormId, 2, employeeId));
        assertSqlRejected(submissionSql(UUID.randomUUID(), "WFS-STALE", instanceId, formId, 2, employeeId));

        UUID submissionId = UUID.randomUUID();
        execute(submissionSql(submissionId, "WFS-VALID", instanceId, formId, 1, employeeId));
        execute(
                "insert into workflow.wf_submission_value(id,tenant_id,submission_id,field_code,value_type,value_text,sensitive_level,is_encrypted) values ('"
                        + UUID.randomUUID() + "','" + TENANT_ID + "','" + submissionId
                        + "','name','TEXT','Alice','P1',false)");
        assertSqlRejected(
                "insert into workflow.wf_submission_value(id,tenant_id,submission_id,field_code,value_type,value_text,sensitive_level,is_encrypted) values ('"
                        + UUID.randomUUID() + "','" + TENANT_ID + "','" + submissionId
                        + "','name','TEXT','Duplicate','P1',false)");
        assertSqlRejected("update workflow.wf_submission_value set value_text='Changed' where submission_id='"
                + submissionId + "'");
        assertSqlRejected("delete from workflow.wf_submission_value where submission_id='" + submissionId + "'");
        assertSqlRejected("update workflow.wf_submission set form_version=2 where id='" + submissionId + "'");
        execute("update workflow.wf_submission set status='RETURNED' where id='" + submissionId + "'");

        assertEquals(1, scalarInt("select form_version from workflow.wf_submission where id='" + submissionId + "'"));
        assertEquals(
                "RETURNED", scalarString("select status from workflow.wf_submission where id='" + submissionId + "'"));
        assertEquals(
                "Alice",
                scalarString("select value_text from workflow.wf_submission_value where submission_id='" + submissionId
                        + "' and field_code='name'"));
    }

    private static String submissionSql(
            UUID id, String no, UUID instanceId, UUID formId, int version, UUID employeeId) {
        return "insert into workflow.wf_submission(id,tenant_id,submission_no,instance_id,form_definition_id,form_version,submitter_id,content_hash,status) values ('"
                + id + "','" + TENANT_ID + "','" + no + "','" + instanceId + "','" + formId + "'," + version
                + ",'" + employeeId + "','content-hash','SUBMITTED')";
    }

    private static UUID createEmployee() throws SQLException {
        UUID id = UUID.randomUUID();
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status) values ('" + id
                + "','" + TENANT_ID + "','PHASE05-FORM-EMP','PHASE05 Form Employee','ACTIVE')");
        return id;
    }

    private static UUID createPublishedVersion(UUID definitionId, int versionNo) throws SQLException {
        UUID versionId = UUID.randomUUID();
        execute(
                "insert into workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum) values ('"
                        + versionId + "','" + TENANT_ID + "','" + definitionId + "'," + versionNo
                        + ",'DRAFT','{}'::jsonb,'draft-form')");
        execute("insert into workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,sort_no) values ('"
                + UUID.randomUUID() + "','" + TENANT_ID + "','" + versionId + "','START','Start','START',10)");
        execute(
                "update workflow.wf_version set status='PUBLISHED',effective_at=now(),checksum='published-form' where id='"
                        + versionId + "'");
        return versionId;
    }

    private static UUID createInstance(UUID definitionId, UUID versionId, UUID employeeId) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        execute(
                "insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,title,initiator_id,current_node_code,status,priority,context_snapshot) values ('"
                        + instanceId + "','" + TENANT_ID + "','WFI-FORM','" + definitionId + "','" + versionId
                        + "','P004','TEST','Form runtime','" + employeeId
                        + "','START','RUNNING','NORMAL','{}'::jsonb)");
        return instanceId;
    }

    private static void assertSqlRejected(String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
        assertTrue(failure.getSQLState() != null && !failure.getSQLState().isBlank());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection("sjg_oms");
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID scalarUuid(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getObject(1, UUID.class);
        }
    }

    private static int scalarInt(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = connection("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        var locations = new java.util.ArrayList<String>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null)
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_ID.toString(),
                        "sjg_tenant_code", "PHASE05_FORM",
                        "sjg_tenant_name", "PHASE-05 Form Test"))
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
