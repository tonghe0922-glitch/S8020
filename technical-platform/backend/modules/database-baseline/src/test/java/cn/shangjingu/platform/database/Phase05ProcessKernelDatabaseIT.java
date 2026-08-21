package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase05ProcessKernelDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID BOOTSTRAP_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final String API_PASSWORD = "p05_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AUDIT_PASSWORD = "p05_audit_" + UUID.randomUUID().toString().replace("-", "");

    private static final List<String> PHASE05_TABLES = List.of(
            "welfare.care_case",
            "document.signature_envelope",
            "document.signature_party",
            "integration.data_import_job",
            "integration.data_import_job_item",
            "integration.dead_letter",
            "audit.data_export_request",
            "audit.data_export_request_item",
            "audit.data_quality_issue",
            "audit.data_quality_issue_item",
            "core.idempotency_record",
            "core.outbox_event");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase05-bootstrap-" + UUID.randomUUID());
        postgres.start();

        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
            statement.execute("CREATE DATABASE sjg_audit");
        }
        migrate("sjg_oms", "oms", "oms");
        migrate("sjg_audit", "audit", "audit");
        seedPhase05Facts();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void approvedPhase05TablesExistAndUseApprovedRlsPolicies() throws Exception {
        try (Connection connection = admin("sjg_oms")) {
            for (String qualified : PHASE05_TABLES) {
                String[] parts = qualified.split("\\.");
                try (PreparedStatement table = connection.prepareStatement("""
                        SELECT c.relrowsecurity,pg_get_userbyid(c.relowner)
                        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p')
                        """)) {
                    table.setString(1, parts[0]);
                    table.setString(2, parts[1]);
                    try (ResultSet result = table.executeQuery()) {
                        assertTrue(result.next(), () -> "missing approved PHASE-05 table " + qualified);
                        assertTrue(result.getBoolean(1), () -> "RLS disabled on " + qualified);
                        assertEquals("sjg_owner", result.getString(2), () -> "unexpected owner for " + qualified);
                    }
                }
                try (PreparedStatement policies = connection.prepareStatement(
                        "SELECT count(*) FROM pg_policies WHERE schemaname=? AND tablename=?")) {
                    policies.setString(1, parts[0]);
                    policies.setString(2, parts[1]);
                    try (ResultSet result = policies.executeQuery()) {
                        assertTrue(result.next());
                        assertTrue(result.getLong(1) >= 1, () -> "no approved tenant policy on " + qualified);
                    }
                }
            }
        }
    }

    @Test
    void apiRuntimeSeesOnlyItsTenantAcrossP016ThroughP020() throws Exception {
        for (String table : List.of(
                "welfare.care_case",
                "document.signature_envelope",
                "integration.data_import_job",
                "audit.data_export_request",
                "audit.data_quality_issue")) {
            assertEquals(1L, runtimeCount(TENANT_A, table), () -> "tenant A isolation failed for " + table);
            assertEquals(1L, runtimeCount(TENANT_B, table), () -> "tenant B isolation failed for " + table);
        }

        UUID tenantBCase = id("00000000-0000-0000-0000-000000005022");
        int changed = inApiTenant(TENANT_A, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE welfare.care_case SET subject='forbidden-cross-tenant' WHERE id=?")) {
                statement.setObject(1, tenantBCase);
                return statement.executeUpdate();
            } catch (SQLException ex) {
                throw new DatabaseTestException(ex);
            }
        });
        assertEquals(0, changed, "cross-tenant update must be filtered by approved RLS");
        try (Connection connection = admin("sjg_oms"); PreparedStatement statement = connection.prepareStatement(
                "SELECT subject FROM welfare.care_case WHERE id=?")) {
            statement.setObject(1, tenantBCase);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("tenant-b-care", result.getString(1));
            }
        }
    }

    @Test
    void idempotencyAndOutboxKeysAreTenantScopedAndDurable() throws Exception {
        String idempotencyKey = "P018-INTEGRATION-SAME-KEY";
        String eventKey = "P018-INTEGRATION-SAME-EVENT";
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            inApiTenant(tenant, connection -> {
                try (PreparedStatement idem = connection.prepareStatement("""
                        INSERT INTO core.idempotency_record(
                            tenant_id,idempotency_key,request_hash,resource_type,resource_id,expire_at)
                        VALUES (?,?,?,?,?,now()+interval '1 hour')
                        """);
                     PreparedStatement outbox = connection.prepareStatement("""
                        INSERT INTO core.outbox_event(
                            tenant_id,aggregate_type,aggregate_id,event_type,event_version,payload,event_key)
                        VALUES (?,?,?,'P018_EXECUTE',1,'{}'::jsonb,?)
                        """)) {
                    UUID resource = UUID.randomUUID();
                    idem.setObject(1, tenant);
                    idem.setString(2, idempotencyKey);
                    idem.setString(3, "sha256-synthetic");
                    idem.setString(4, "integration.data_import_job");
                    idem.setObject(5, resource);
                    assertEquals(1, idem.executeUpdate());
                    outbox.setObject(1, tenant);
                    outbox.setString(2, "integration.data_import_job");
                    outbox.setObject(3, resource);
                    outbox.setString(4, eventKey);
                    assertEquals(1, outbox.executeUpdate());
                    return null;
                } catch (SQLException ex) {
                    throw new DatabaseTestException(ex);
                }
            });
        }
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            long idempotencyCount = inApiTenant(tenant, connection -> count(connection,
                    "SELECT count(*) FROM core.idempotency_record WHERE idempotency_key='" + idempotencyKey + "'"));
            long outboxCount = inApiTenant(tenant, connection -> count(connection,
                    "SELECT count(*) FROM core.outbox_event WHERE event_key='" + eventKey + "'"));
            assertEquals(1L, idempotencyCount);
            assertEquals(1L, outboxCount);
        }
    }

    @Test
    void runtimeRolesRemainLeastPrivilegeForPhase05Tables() throws Exception {
        try (Connection connection = admin("sjg_oms")) {
            assertTrue(hasTablePrivilege(connection, "sjg_api_runtime", "welfare.care_case", "SELECT"));
            assertTrue(hasTablePrivilege(connection, "sjg_api_runtime", "audit.data_export_request", "UPDATE"));
            assertTrue(hasTablePrivilege(connection, "sjg_worker_runtime", "core.outbox_event", "SELECT"));
            assertTrue(hasTablePrivilege(connection, "sjg_worker_runtime", "integration.dead_letter", "INSERT"));
            assertFalse(hasSchemaPrivilege(connection, "sjg_api_runtime", "welfare", "CREATE"));
            assertFalse(hasSchemaPrivilege(connection, "sjg_worker_runtime", "integration", "CREATE"));
        }
    }

    @Test
    void workerAuditSqlMatchesImmutableAuditContract() throws Exception {
        UUID eventId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(jdbcUrl("sjg_audit"), "sjg_audit_writer", AUDIT_PASSWORD)) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                context.setString(1, TENANT_A.toString());
                context.executeQuery();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO audit.operation_log(tenant_id,action,resource_type,resource_id,request_id)
                    VALUES (?,?,?,?,?)
                    """)) {
                insert.setObject(1, TENANT_A);
                insert.setString(2, "P018_WORKER_EXECUTE_ATTEMPT");
                insert.setString(3, "integration.data_import_job");
                insert.setObject(4, id("00000000-0000-0000-0000-000000005031"));
                insert.setString(5, "worker-" + eventId);
                assertEquals(1, insert.executeUpdate());
            }
            connection.commit();

            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                context.setString(1, TENANT_A.toString());
                context.executeQuery();
            }
            SQLException denied = assertThrows(SQLException.class, () -> {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE audit.operation_log SET action='MUTATED' WHERE request_id=?")) {
                    update.setString(1, "worker-" + eventId);
                    update.executeUpdate();
                }
            });
            assertNotNull(denied.getSQLState());
            connection.rollback();
        }
    }

    private static void seedPhase05Facts() throws Exception {
        try (Connection connection = admin("sjg_oms")) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement(
                    "INSERT INTO core.tenant(id,tenant_code,tenant_name,status,timezone) VALUES (?,?,?,'ACTIVE','Asia/Shanghai')")) {
                insertTenant(tenant, TENANT_A, "P05_A", "PHASE-05 Tenant A");
                insertTenant(tenant, TENANT_B, "P05_B", "PHASE-05 Tenant B");
            }
            seedTenantFacts(connection, TENANT_A, 5010, "tenant-a");
            seedTenantFacts(connection, TENANT_B, 5020, "tenant-b");
            connection.commit();
        }
    }

    private static void insertTenant(PreparedStatement statement, UUID tenant, String code, String name) throws SQLException {
        statement.setObject(1, tenant);
        statement.setString(2, code);
        statement.setString(3, name);
        assertEquals(1, statement.executeUpdate());
    }

    private static void seedTenantFacts(Connection connection, UUID tenant, int base, String label) throws SQLException {
        UUID care = id(suffix(base + 2));
        UUID sourceFile = id(suffix(base + 3));
        UUID signature = id(suffix(base + 4));
        UUID importJob = id(suffix(base + 5));
        UUID export = id(suffix(base + 6));
        UUID quality = id(suffix(base + 7));

        try (PreparedStatement file = connection.prepareStatement("""
                INSERT INTO document.file_object(
                    id,tenant_id,object_key,original_name,content_type,size_bytes,sha256,storage_bucket,virus_scan_status)
                VALUES (?,?,?,?,?,1,?,'phase05-test','CLEAN')
                """)) {
            file.setObject(1, sourceFile);
            file.setObject(2, tenant);
            file.setString(3, label + "/source.csv");
            file.setString(4, "source.csv");
            file.setString(5, "text/csv");
            file.setString(6, "0000000000000000000000000000000000000000000000000000000000000000");
            assertEquals(1, file.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO welfare.care_case(
                    id,tenant_id,business_no,status,cost_center_id,currency,employee_event_type,
                    fact_occurred_at,fact_summary,impact_level,subject)
                VALUES (?,?,?,'S01','P05-COST','CNY','SYNTHETIC',now(),'synthetic fact','LOW',?)
                """)) {
            statement.setObject(1, care);
            statement.setObject(2, tenant);
            statement.setString(3, "P016-" + label);
            statement.setString(4, label + "-care");
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document.signature_envelope(
                    id,tenant_id,business_no,envelope_no,document_hash,signing_order,sign_status,
                    actual_start_at,authentication_method,business_date,document_type,result_summary)
                VALUES (?,?,?,?,?,'SEQUENTIAL','PENDING',now(),'MFA',current_date,'SYNTHETIC','synthetic')
                """)) {
            statement.setObject(1, signature);
            statement.setObject(2, tenant);
            statement.setString(3, "P017-" + label);
            statement.setString(4, "ENV-" + label);
            statement.setString(5, "sha256-" + label);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO integration.data_import_job(
                    id,tenant_id,business_no,import_type,source_file_id,template_version,
                    actual_start_at,business_date,environment,result_summary,system_service_name,tech_impact_scope,tech_risk_level)
                VALUES (?,?,?,'SYNTHETIC',?,'v1',now(),current_date,'TEST','synthetic','phase05','integration-test','LOW')
                """)) {
            statement.setObject(1, importJob);
            statement.setObject(2, tenant);
            statement.setString(3, "P018-" + label);
            statement.setObject(4, sourceFile);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO integration.data_import_job_item(tenant_id,master_id,field_code,item_value_text)
                VALUES (?,?,'tech_logs_reports','synthetic')
                """)) {
            statement.setObject(1, tenant);
            statement.setObject(2, importJob);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit.data_export_request(
                    id,tenant_id,business_no,export_type,data_scope,field_scope,purpose,approval_level,
                    actual_start_at,business_date,environment,result_summary,system_service_name,tech_impact_scope,tech_risk_level)
                VALUES (?,?,?,'SYNTHETIC','{}'::jsonb,'{}'::jsonb,'integration-test','L1',
                    now(),current_date,'TEST','synthetic','phase05','integration-test','LOW')
                """)) {
            statement.setObject(1, export);
            statement.setObject(2, tenant);
            statement.setString(3, "P019-" + label);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit.data_export_request_item(tenant_id,master_id,field_code,item_value_text)
                VALUES (?,?,'tech_logs_reports','synthetic')
                """)) {
            statement.setObject(1, tenant);
            statement.setObject(2, export);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit.data_quality_issue(
                    id,tenant_id,business_no,rule_code,object_type,issue_type,severity,
                    actual_start_at,business_date,employee_event_type,environment,result_summary,
                    system_service_name,tech_impact_scope,tech_risk_level,before_snapshot)
                VALUES (?,?,?,'P05_RULE','SYNTHETIC','DATA_QUALITY','LOW',now(),current_date,'SYNTHETIC',
                    'TEST','synthetic','phase05','integration-test','LOW','{}'::jsonb)
                """)) {
            statement.setObject(1, quality);
            statement.setObject(2, tenant);
            statement.setString(3, "P020-" + label);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit.data_quality_issue_item(tenant_id,master_id,field_code,item_value_text)
                VALUES (?,?,'approved_repair_plan','synthetic')
                """)) {
            statement.setObject(1, tenant);
            statement.setObject(2, quality);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static long runtimeCount(UUID tenant, String table) {
        return inApiTenant(tenant, connection -> count(connection, "SELECT count(*) FROM " + table));
    }

    private static long count(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        } catch (SQLException ex) {
            throw new DatabaseTestException(ex);
        }
    }

    private static <T> T inApiTenant(UUID tenant, Function<Connection, T> work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD)) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                context.setString(1, tenant.toString());
                context.executeQuery();
            }
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException ex) {
            throw new DatabaseTestException(ex);
        }
    }

    private static boolean hasTablePrivilege(Connection connection, String role, String table, String privilege) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT has_table_privilege(?,?,?)")) {
            statement.setString(1, role);
            statement.setString(2, table);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean hasSchemaPrivilege(Connection connection, String role, String schema, String privilege) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT has_schema_privilege(?,?,?)")) {
            statement.setString(1, role);
            statement.setString(2, schema);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", BOOTSTRAP_TENANT.toString(),
                        "sjg_tenant_code", "PHASE05_GATE",
                        "sjg_tenant_name", "PHASE-05 Gate Tenant"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success, () -> "Flyway migration failed for " + database);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted, () -> "Flyway repeatability failed for " + database);
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
                    && Files.isDirectory(current.resolve("technical-platform/database/flyway/oms"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }

    private static String suffix(int value) {
        return "00000000-0000-0000-0000-" + String.format("%012d", value);
    }

    private static final class DatabaseTestException extends RuntimeException {
        DatabaseTestException(Throwable cause) {
            super(cause);
        }
    }
}
