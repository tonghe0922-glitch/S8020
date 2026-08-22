package cn.shangjingu.platform.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PHASE-10 / P006-P010 Spring Boot + PostgreSQL16 + Redis browser fixture. */
public final class Phase10BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD = "phase10_api_" + shortId();
    private static final String AUDIT_PASSWORD = "phase10_audit_" + shortId();

    private Phase10BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE10_E2E_TENANT");
        String employeeLogin = requiredEnv("PHASE10_E2E_LOGIN");
        String password = requiredEnv("PHASE10_E2E_PASSWORD");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, employeeLogin, password);
        ConfigurableApplicationContext context = startApi(postgres, redis);
        writeRuntimeFacts(postgres, redis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            redis.stop();
            postgres.stop();
        }));
        System.out.println("PHASE10_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18110",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl(postgres, "sjg_oms"),
                "--spring.datasource.username=sjg_api_runtime",
                "--spring.datasource.password=" + API_PASSWORD,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--sjg.audit.datasource.url=" + jdbcUrl(postgres, "sjg_audit"),
                "--sjg.audit.datasource.username=sjg_audit_writer",
                "--sjg.audit.datasource.password=" + AUDIT_PASSWORD,
                "--sjg.security.session.access-ttl=PT30M",
                "--sjg.security.session.refresh-ttl=PT1H");
    }

    private static void prepareDatabases(
            PostgreSQLContainer<?> postgres, String tenantCode, String employeeLogin, String password)
            throws Exception {
        Path root = findRepoRoot();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true)
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
            statement.execute("CREATE DATABASE sjg_audit");
        }
        Flyway.configure()
                .dataSource(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword())
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway/oms"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/oms"))
                .placeholders(Map.of(
                        "sjg_tenant_id",
                        Phase10FixtureData.TENANT.toString(),
                        "sjg_tenant_code",
                        tenantCode,
                        "sjg_tenant_name",
                        "PHASE10 Browser Tenant"))
                .cleanDisabled(true)
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(jdbcUrl(postgres, "sjg_audit"), postgres.getUsername(), postgres.getPassword())
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway/audit"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/audit"))
                .cleanDisabled(true)
                .load()
                .migrate();
        Phase10FixtureData.seed(postgres, employeeLogin, password);
        Phase10ProfessionalCertifierData.seed(postgres, password);
    }

    private static void writeRuntimeFacts(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) throws Exception {
        Path output = findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase10-fixture-runtime.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                "{\n"
                        + "  \"postgresContainerId\": \"" + postgres.getContainerId() + "\",\n"
                        + "  \"redisContainerId\": \"" + redis.getContainerId() + "\",\n"
                        + "  \"tenantId\": \"" + Phase10FixtureData.TENANT + "\",\n"
                        + "  \"centerAId\": \"" + Phase10FixtureData.CENTER_A + "\",\n"
                        + "  \"centerBId\": \"" + Phase10FixtureData.CENTER_B + "\",\n"
                        + "  \"employeeId\": \"" + Phase10FixtureData.EMPLOYEE + "\",\n"
                        + "  \"managerEmployeeId\": \"" + Phase10FixtureData.MANAGER + "\",\n"
                        + "  \"techEmployeeId\": \"" + Phase10FixtureData.TECH + "\",\n"
                        + "  \"outEmployeeId\": \"" + Phase10FixtureData.OUT + "\",\n"
                        + "  \"qualificationRoleId\": \"" + Phase10FixtureData.QUALIFICATION_ROLE + "\"\n"
                        + "}\n");
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required environment missing: " + name);
        }
        return value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static Path findRepoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("mvnw")) && Files.isDirectory(cursor.resolve("technical-platform"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
