package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase03MigrationRoleTest {

    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final String TEST_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void nonSuperuserMigrationRoleInstallsAllThreeDatabases() throws Exception {
        Path root = findRepoRoot();
        String migrationPassword = "phase03_" + UUID.randomUUID().toString().replace("-", "");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID())) {
            postgres.start();

            Flyway cluster = Flyway.configure()
                    .dataSource(jdbcUrl(postgres, "postgres"), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                    .cleanDisabled(true)
                    .load();
            assertTrue(cluster.migrate().success);
            cluster.validate();

            try (Connection connection = DriverManager.getConnection(
                            jdbcUrl(postgres, "postgres"), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER ROLE sjg_migration PASSWORD '" + migrationPassword + "'");
                for (String database : List.of("sjg_oms", "sjg_audit", "sjg_dw")) {
                    statement.execute("CREATE DATABASE " + database + " OWNER sjg_owner");
                }
            }

            for (Map.Entry<String, String> entry : Map.of(
                            "sjg_oms", "oms",
                            "sjg_audit", "audit",
                            "sjg_dw", "dw")
                    .entrySet()) {
                Flyway flyway = Flyway.configure()
                        .dataSource(jdbcUrl(postgres, entry.getKey()), "sjg_migration", migrationPassword)
                        .locations(
                                "filesystem:"
                                        + root.resolve("technical-platform/database/flyway")
                                                .resolve(entry.getValue()),
                                "filesystem:"
                                        + root.resolve("technical-platform/database/flyway-overlays")
                                                .resolve(entry.getValue()))
                        .placeholders(Map.of(
                                "sjg_tenant_id", TEST_TENANT_ID,
                                "sjg_tenant_code", "PHASE03_MIGRATION_ROLE_TEST",
                                "sjg_tenant_name", "PHASE-03 Migration Role Test Tenant"))
                        .initSql("SET ROLE sjg_owner")
                        .cleanDisabled(true)
                        .load();

                MigrateResult first = flyway.migrate();
                assertTrue(first.success, () -> "sjg_migration failed to install " + entry.getKey());
                flyway.validate();
                assertEquals(
                        0,
                        flyway.migrate().migrationsExecuted,
                        () -> "second migrate must be empty for " + entry.getKey());

                try (Connection connection = DriverManager.getConnection(
                                jdbcUrl(postgres, entry.getKey()), "sjg_migration", migrationPassword);
                        Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT current_user, session_user")) {
                    assertTrue(result.next());
                    assertEquals("sjg_migration", result.getString(1));
                    assertEquals("sjg_migration", result.getString(2));
                }
            }
        }
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
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
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
