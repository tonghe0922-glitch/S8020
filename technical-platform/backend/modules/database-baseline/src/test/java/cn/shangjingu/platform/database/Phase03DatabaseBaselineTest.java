package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase03DatabaseBaselineTest {

    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TEST_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEST_TENANT_CODE = "PHASE03_TEST";
    private static final String TEST_TENANT_NAME = "PHASE-03 Test Tenant";
    private static final List<String> DATABASES = List.of("sjg_oms", "sjg_audit", "sjg_dw");
    private static final Map<String, String> SOURCE_DIR_BY_DATABASE = Map.of(
            "sjg_oms", "01_sjg_oms",
            "sjg_audit", "02_sjg_audit",
            "sjg_dw", "03_sjg_dw");
    private static final List<String> FORMAL_ROLES = List.of(
            "sjg_owner",
            "sjg_migration",
            "sjg_api_runtime",
            "sjg_worker_runtime",
            "sjg_audit_writer",
            "sjg_auditor",
            "sjg_dw_writer",
            "sjg_dw_reader");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)");
    private static final Set<String> APPROVED_OVERLAY_CREATE_TABLES = Set.of(
            "01_sjg_oms:collaboration.notice_receipt_event",
            "01_sjg_oms:collaboration.notice_recipient",
            "01_sjg_oms:iam.mfa_credential",
            "01_sjg_oms:iam.permission_request_grant",
            "01_sjg_oms:integration.webhook_event",
            "01_sjg_oms:learning.learning_assignment_evidence",
            "01_sjg_oms:learning.qualification_permission_binding",
            "01_sjg_oms:performance.performance_score_entry",
            "01_sjg_oms:reward.point_balance_snapshot",
            "01_sjg_oms:reward.point_rule_version",
            "01_sjg_oms:reward.point_source_guard",
            "01_sjg_oms:welfare.care_case_fact");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installBaselineFromEmptyPostgres16() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase03-test-only");
        postgres.start();

        migrate("postgres", "cluster", null);
        createCoreDatabases();
        migrate("sjg_oms", "oms", "oms");
        migrate("sjg_audit", "audit", "audit");
        migrate("sjg_dw", "dw", "dw");
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void flywayMigrationIsRepeatableAndValidated() {
        for (String database : List.of("postgres", "sjg_oms", "sjg_audit", "sjg_dw")) {
            String folder =
                    switch (database) {
                        case "postgres" -> "cluster";
                        case "sjg_oms" -> "oms";
                        case "sjg_audit" -> "audit";
                        case "sjg_dw" -> "dw";
                        default -> throw new IllegalStateException(database);
                    };
            Flyway flyway = flyway(database, folder, database.equals("postgres") ? null : folder);
            flyway.validate();
            MigrateResult second = flyway.migrate();
            assertEquals(0, second.migrationsExecuted, () -> "second migrate must be empty for " + database);
        }
    }

    @Test
    void schemaAndTableCountsMatchInstalledApprovedDdl() throws Exception {
        long schemas = 0;
        for (String database : DATABASES) {
            try (Connection connection = connection(database)) {
                schemas += scalarLong(
                        connection,
                        """
            SELECT count(*) FROM information_schema.schemata
            WHERE schema_name NOT IN ('public','information_schema') AND schema_name NOT LIKE 'pg_%'
            """);
            }
        }
        assertEquals(46, schemas, "physical non-system Schema count must match KB baseline");

        Set<String> approved = approvedCreateTableNames();
        Set<String> overlay = overlayCreateTableNames();
        assertEquals(
                APPROVED_OVERLAY_CREATE_TABLES,
                overlay,
                "overlay CREATE TABLE declarations require explicit review before entering the installed baseline");

        Set<String> duplicates = new HashSet<>(approved);
        duplicates.retainAll(overlay);
        assertTrue(duplicates.isEmpty(), () -> "overlay tables duplicate approved source DDL: " + duplicates);

        Set<String> expected = new HashSet<>(approved);
        expected.addAll(overlay);
        Set<String> installed = installedTableNames();
        assertEquals(
                expected,
                installed,
                "installed tables must equal approved source DDL plus the explicitly reviewed overlay table set");
        assertTrue(
                approved.size() >= 265,
                "approved DDL must cover the 265-table catalog; source/catalog diff is reported separately");
    }

    @Test
    void everyTenantTableHasRlsAndPolicy() throws Exception {
        int inspected = 0;
        for (String database : DATABASES) {
            try (Connection connection = connection(database);
                    PreparedStatement statement = connection.prepareStatement(
                            """
               SELECT DISTINCT c.table_schema,c.table_name
               FROM information_schema.columns c
               JOIN information_schema.tables t USING (table_schema,table_name)
               WHERE c.column_name='tenant_id' AND t.table_type='BASE TABLE'
                 AND c.table_schema NOT IN ('public','information_schema') AND c.table_schema NOT LIKE 'pg_%'
               ORDER BY c.table_schema,c.table_name
               """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        inspected++;
                        assertRlsEnabled(connection, database, rows.getString(1), rows.getString(2));
                    }
                }
            }
        }
        assertTrue(inspected > 0, "RLS test must inspect real tenant tables");
    }

    @Test
    void formalRolesAreNonPrivilegedAndMigrationCanSetOwnerRole() throws Exception {
        try (Connection connection = connection("postgres")) {
            for (String role : FORMAL_ROLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        """
            SELECT rolcanlogin,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls
            FROM pg_roles WHERE rolname=?
            """)) {
                    statement.setString(1, role);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next(), () -> "missing role " + role);
                        boolean login = result.getBoolean(1);
                        for (int column = 2; column <= 6; column++) {
                            assertFalse(
                                    result.getBoolean(column),
                                    role + " has forbidden cluster privilege at column " + column);
                        }
                        if (role.equals("sjg_owner")) assertFalse(login, "owner must be NOLOGIN");
                        else
                            assertTrue(
                                    login,
                                    () -> role + " must be a login principal with secret provisioned outside Git");
                    }
                }
            }
            assertTrue(hasRole(connection, "sjg_migration", "sjg_owner"));
        }
    }

    @Test
    void databaseAndSchemaPrivilegesAreLeastPrivilege() throws Exception {
        try (Connection connection = connection("postgres")) {
            assertTrue(hasDatabasePrivilege(connection, "sjg_api_runtime", "sjg_oms", "CONNECT"));
            assertTrue(hasDatabasePrivilege(connection, "sjg_worker_runtime", "sjg_oms", "CONNECT"));
            assertFalse(hasDatabasePrivilege(connection, "sjg_api_runtime", "sjg_audit", "CONNECT"));
            assertFalse(hasDatabasePrivilege(connection, "sjg_api_runtime", "sjg_dw", "CONNECT"));
            assertTrue(hasDatabasePrivilege(connection, "sjg_audit_writer", "sjg_audit", "CONNECT"));
            assertTrue(hasDatabasePrivilege(connection, "sjg_auditor", "sjg_audit", "CONNECT"));
            assertTrue(hasDatabasePrivilege(connection, "sjg_dw_writer", "sjg_dw", "CONNECT"));
            assertTrue(hasDatabasePrivilege(connection, "sjg_dw_reader", "sjg_dw", "CONNECT"));
            assertFalse(hasDatabasePrivilege(connection, "sjg_api_runtime", "sjg_oms", "CREATE"));
            assertFalse(hasDatabasePrivilege(connection, "sjg_worker_runtime", "sjg_oms", "CREATE"));
        }
        try (Connection connection = connection("sjg_oms")) {
            assertTrue(hasSchemaPrivilege(connection, "sjg_api_runtime", "core", "USAGE"));
            assertFalse(hasSchemaPrivilege(connection, "sjg_api_runtime", "core", "CREATE"));
            assertFalse(hasSchemaPrivilege(connection, "sjg_worker_runtime", "workflow", "CREATE"));
        }
    }

    @Test
    void tenantBootstrapUsesExplicitDeploymentFacts() throws Exception {
        try (Connection connection = connection("sjg_oms");
                PreparedStatement statement =
                        connection.prepareStatement("SELECT tenant_code,tenant_name FROM core.tenant WHERE id=?")) {
            statement.setObject(1, TEST_TENANT_ID);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(TEST_TENANT_CODE, result.getString(1));
                assertEquals(TEST_TENANT_NAME, result.getString(2));
            }
        }
        try (Connection connection = connection("sjg_oms")) {
            assertEquals(
                    126,
                    scalarLong(
                            connection,
                            "SELECT count(*) FROM workflow.wf_definition WHERE tenant_id='" + TEST_TENANT_ID + "'"));
        }
    }

    @Test
    void auditWriterCanAppendAndReadButCannotMutateHistory() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        try (Connection connection = connection("sjg_audit");
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO audit.access_log(tenant_id,resource_type,purpose) VALUES ('" + tenantA
                    + "','phase03','tenant-a')");
            statement.executeUpdate("INSERT INTO audit.access_log(tenant_id,resource_type,purpose) VALUES ('" + tenantB
                    + "','phase03','tenant-b')");
        }
        try (Connection connection = connection("sjg_audit");
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE sjg_audit_writer");
            statement.execute("SET app.tenant_id='" + tenantA + "'");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM audit.access_log")) {
                assertTrue(result.next());
                assertEquals(1, result.getLong(1), "RLS must hide other-tenant audit rows");
            }
            assertEquals(
                    1,
                    statement.executeUpdate("INSERT INTO audit.access_log(tenant_id,resource_type,purpose) VALUES ('"
                            + tenantA + "','phase03','writer-append')"));
        }
        assertSqlDenied("UPDATE audit.access_log SET purpose='forbidden' WHERE tenant_id='" + tenantA + "'", tenantA);
        assertSqlDenied("DELETE FROM audit.access_log WHERE tenant_id='" + tenantA + "'", tenantA);
        assertSqlDenied("TRUNCATE TABLE audit.access_log", tenantA);
    }

    @Test
    void approvedTablesAreOwnedByNonLoginOwner() throws Exception {
        for (String database : DATABASES) {
            try (Connection connection = connection(database);
                    PreparedStatement statement = connection.prepareStatement(
                            """
               SELECT DISTINCT pg_get_userbyid(c.relowner)
               FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE c.relkind IN ('r','p')
                 AND n.nspname NOT IN ('public','information_schema') AND n.nspname NOT LIKE 'pg_%'
               """)) {
                List<String> owners = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) owners.add(result.getString(1));
                }
                assertFalse(owners.isEmpty(), () -> "no approved tables found in " + database);
                assertEquals(
                        List.of("sjg_owner"),
                        owners.stream().distinct().sorted().toList(),
                        () -> "runtime/bootstrap role owns approved tables in " + database);
            }
        }
    }

    private static Flyway flyway(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        }
        return Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TEST_TENANT_ID.toString(),
                        "sjg_tenant_code", TEST_TENANT_CODE,
                        "sjg_tenant_name", TEST_TENANT_NAME))
                .cleanDisabled(true)
                .load();
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        Flyway flyway = flyway(database, generatedFolder, overlayFolder);
        assertTrue(flyway.migrate().success, () -> "Flyway migrate failed for " + database);
        flyway.validate();
    }

    private static void createCoreDatabases() throws SQLException {
        try (Connection connection = connection("postgres");
                Statement statement = connection.createStatement()) {
            for (String database : DATABASES) statement.execute("CREATE DATABASE " + database);
        }
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

    private static void assertRlsEnabled(Connection connection, String database, String schema, String table)
            throws SQLException {
        try (PreparedStatement state = connection.prepareStatement(
                """
        SELECT c.relrowsecurity,pg_get_userbyid(c.relowner)
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p')
        """)) {
            state.setString(1, schema);
            state.setString(2, table);
            try (ResultSet result = state.executeQuery()) {
                assertTrue(result.next(), () -> "missing tenant table " + database + "." + schema + "." + table);
                assertTrue(result.getBoolean(1), () -> "RLS disabled on " + database + "." + schema + "." + table);
                assertEquals("sjg_owner", result.getString(2));
            }
        }
        try (PreparedStatement policies =
                connection.prepareStatement("SELECT count(*) FROM pg_policies WHERE schemaname=? AND tablename=?")) {
            policies.setString(1, schema);
            policies.setString(2, table);
            try (ResultSet result = policies.executeQuery()) {
                assertTrue(result.next());
                assertTrue(
                        result.getLong(1) >= 1,
                        () -> "tenant table has no policy: " + database + "." + schema + "." + table);
            }
        }
    }

    private static boolean hasRole(Connection connection, String member, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_has_role(?,?,'MEMBER')")) {
            statement.setString(1, member);
            statement.setString(2, role);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean hasDatabasePrivilege(Connection connection, String role, String database, String privilege)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT has_database_privilege(?,?,?)")) {
            statement.setString(1, role);
            statement.setString(2, database);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean hasSchemaPrivilege(Connection connection, String role, String schema, String privilege)
            throws SQLException {
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

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void assertSqlDenied(String sql, UUID tenant) throws SQLException {
        try (Connection connection = connection("sjg_audit");
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE sjg_audit_writer");
            statement.execute("SET app.tenant_id='" + tenant + "'");
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertNotNull(failure.getSQLState());
        }
    }

    private static Set<String> installedTableNames() throws SQLException {
        Set<String> tables = new HashSet<>();
        for (String database : DATABASES) {
            String sourceDir = SOURCE_DIR_BY_DATABASE.get(database);
            try (Connection connection = connection(database);
                    PreparedStatement statement = connection.prepareStatement(
                            """
               SELECT table_schema,table_name
               FROM information_schema.tables
               WHERE table_type='BASE TABLE'
                 AND table_schema NOT IN ('public','information_schema') AND table_schema NOT LIKE 'pg_%'
               ORDER BY table_schema,table_name
               """);
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tables.add(sourceDir + ":" + result.getString(1) + "." + result.getString(2));
                }
            }
        }
        return tables;
    }

    private static Set<String> approvedCreateTableNames() throws IOException {
        Set<String> tables = new HashSet<>();
        for (String dir : SOURCE_DIR_BY_DATABASE.values()) {
            Path sourceDir =
                    repoRoot.resolve("Knowledge Base/03 数据库需求规则/03_SQL_DDL").resolve(dir);
            tables.addAll(createTableNames(sourceDir, dir));
        }
        return tables;
    }

    private static Set<String> overlayCreateTableNames() throws IOException {
        Set<String> tables = new HashSet<>();
        for (Map.Entry<String, String> entry : SOURCE_DIR_BY_DATABASE.entrySet()) {
            Path sourceDir = repoRoot.resolve("technical-platform/database/flyway-overlays")
                    .resolve(
                            switch (entry.getKey()) {
                                case "sjg_oms" -> "oms";
                                case "sjg_audit" -> "audit";
                                case "sjg_dw" -> "dw";
                                default -> throw new IllegalStateException(entry.getKey());
                            });
            tables.addAll(createTableNames(sourceDir, entry.getValue()));
        }
        return tables;
    }

    private static Set<String> createTableNames(Path sourceDir, String prefix) throws IOException {
        Set<String> tables = new HashSet<>();
        try (var paths = Files.list(sourceDir)) {
            for (Path sql : paths.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                Matcher matcher = CREATE_TABLE.matcher(Files.readString(sql, StandardCharsets.UTF_8));
                while (matcher.find()) tables.add(prefix + ":" + matcher.group(1) + "." + matcher.group(2));
            }
        }
        return tables;
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
