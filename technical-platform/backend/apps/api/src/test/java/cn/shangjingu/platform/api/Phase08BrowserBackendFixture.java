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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class Phase08BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD =
            "phase08_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AUDIT_PASSWORD =
            "phase08_audit_" + UUID.randomUUID().toString().replace("-", "");

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000881");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000881");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000882");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000881");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000882");
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000881");
    private static final UUID APPOINTMENT_A = UUID.fromString("40000000-0000-0000-0000-000000000881");
    private static final UUID APPOINTMENT_B = UUID.fromString("40000000-0000-0000-0000-000000000882");
    private static final UUID USER = UUID.fromString("50000000-0000-0000-0000-000000000881");
    private static final UUID IDENTITY_A = UUID.fromString("60000000-0000-0000-0000-000000000881");
    private static final UUID IDENTITY_B = UUID.fromString("60000000-0000-0000-0000-000000000882");
    private static final UUID ROLE_A = UUID.fromString("70000000-0000-0000-0000-000000000881");
    private static final UUID ROLE_B = UUID.fromString("70000000-0000-0000-0000-000000000882");
    private static final UUID PERMISSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000881");
    private static final UUID PERMISSION_SWITCH = UUID.fromString("80000000-0000-0000-0000-000000000882");
    private static final UUID PERMISSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000883");

    private Phase08BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE08_E2E_TENANT");
        String loginName = requiredEnv("PHASE08_E2E_LOGIN");
        String loginSecret = requiredEnv("PHASE08_E2E_PASSWORD");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, loginName, loginSecret);
        ConfigurableApplicationContext context = startApi(postgres, redis);
        writeRuntimeFacts(postgres);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            redis.stop();
            postgres.stop();
        }));
        System.out.println("PHASE08_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18080",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl(postgres, "sjg_oms"),
                "--spring.datasource.username=sjg_api_runtime",
                "--spring.datasource.password=" + API_PASSWORD,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--sjg.audit.datasource.url=" + jdbcUrl(postgres, "sjg_audit"),
                "--sjg.audit.datasource.username=sjg_audit_writer",
                "--sjg.audit.datasource.password=" + AUDIT_PASSWORD,
                "--sjg.security.session.access-ttl=PT20S",
                "--sjg.security.session.refresh-ttl=PT5M",
                "--sjg.security.step-up.ticket-ttl=PT5M");
    }

    private static void prepareDatabases(
            PostgreSQLContainer<?> postgres, String tenantCode, String loginName, String loginSecret) throws Exception {
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
                        TENANT.toString(),
                        "sjg_tenant_code",
                        tenantCode,
                        "sjg_tenant_name",
                        "PHASE08 Browser E2E Tenant"))
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
        seedSyntheticFacts(postgres, loginName, loginSecret);
    }

    private static void seedSyntheticFacts(PostgreSQLContainer<?> postgres, String loginName, String loginSecret)
            throws Exception {
        String passwordHash = new BCryptPasswordEncoder(12).encode(loginSecret);
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('"
                            + CENTER_A + "','" + TENANT
                            + "','PHASE08_CENTER_A','Synthetic Center A','CENTER','phase08_center_a'::ltree,'ACTIVE'),('"
                            + CENTER_B + "','" + TENANT
                            + "','PHASE08_CENTER_B','Synthetic Center B','CENTER','phase08_center_b'::ltree,'ACTIVE')");
            statement.execute(
                    "INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('"
                            + POSITION_A + "','" + TENANT + "','PHASE08_POS_A','Synthetic Position A','" + CENTER_A
                            + "','ACTIVE'),('" + POSITION_B + "','" + TENANT
                            + "','PHASE08_POS_B','Synthetic Position B','" + CENTER_B + "','ACTIVE')");
            statement.execute(
                    "INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('"
                            + EMPLOYEE + "','" + TENANT
                            + "','PHASE08-E001','Synthetic Browser User','ACTIVE',current_date-10,'" + CENTER_A + "','"
                            + POSITION_A + "')");
            statement.execute(
                    "INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('"
                            + APPOINTMENT_A + "','" + TENANT + "','" + EMPLOYEE + "','" + POSITION_A + "','" + CENTER_A
                            + "',true,current_date-10,'ACTIVE'),('" + APPOINTMENT_B + "','" + TENANT + "','" + EMPLOYEE
                            + "','" + POSITION_B + "','" + CENTER_B + "',false,current_date-5,'ACTIVE')");
            statement.execute(
                    "INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('"
                            + USER + "','" + TENANT + "','" + loginName + "','" + passwordHash + "','ACTIVE',0)");
            statement.execute(
                    "INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('"
                            + IDENTITY_A + "','" + TENANT + "','" + USER + "','" + EMPLOYEE
                            + "','EMPLOYEE','Synthetic E2E Identity A','" + CENTER_A + "','" + POSITION_A
                            + "',true,now()-interval '1 day'),('" + IDENTITY_B + "','" + TENANT + "','" + USER + "','"
                            + EMPLOYEE + "','EMPLOYEE','Synthetic E2E Identity B','" + CENTER_B + "','" + POSITION_B
                            + "',false,now()-interval '1 day')");
            statement.execute(
                    "INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES ('"
                            + TENANT
                            + "','PHASE08_CENTER','Browser Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true)");
            statement.execute(
                    "INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('"
                            + ROLE_A + "','" + TENANT
                            + "','PHASE08_ROLE_A','Browser Identity A Role','PLATFORM','PHASE08_CENTER',true),('"
                            + ROLE_B + "','" + TENANT
                            + "','PHASE08_ROLE_B','Browser Identity B Role','PLATFORM','PHASE08_CENTER',true)");
            statement.execute(
                    "INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES ('"
                            + PERMISSION_READ + "','" + TENANT
                            + "','platform.session.read','Read current session','SESSION','READ','NORMAL'),('"
                            + PERMISSION_SWITCH + "','" + TENANT
                            + "','platform.session.switch','Switch current identity','SESSION','SWITCH','HIGH'),('"
                            + PERMISSION_LOGOUT + "','" + TENANT
                            + "','platform.session.logout','Logout current session','SESSION','LOGOUT','NORMAL')");
            statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES ('" + TENANT
                    + "','" + ROLE_A + "','" + PERMISSION_READ + "'),('" + TENANT + "','" + ROLE_A + "','"
                    + PERMISSION_SWITCH + "'),('" + TENANT + "','" + ROLE_A + "','" + PERMISSION_LOGOUT + "'),('"
                    + TENANT + "','" + ROLE_B + "','" + PERMISSION_READ + "'),('" + TENANT + "','" + ROLE_B + "','"
                    + PERMISSION_LOGOUT + "')");
            statement.execute(
                    "INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('"
                            + TENANT + "','" + USER + "','" + IDENTITY_A + "','" + ROLE_A
                            + "',now()-interval '1 day','TEST_ONLY'),('" + TENANT + "','" + USER + "','" + IDENTITY_B
                            + "','" + ROLE_B + "',now()-interval '1 day','TEST_ONLY')");
        }
    }

    private static void writeRuntimeFacts(PostgreSQLContainer<?> postgres) throws Exception {
        Path output = findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase08-fixture-runtime.json");
        String json = "{\n"
                + "  \"postgresContainerId\": \"" + postgres.getContainerId() + "\",\n"
                + "  \"tenantId\": \"" + TENANT + "\"\n"
                + "}\n";
        Files.writeString(output, json);
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
                    && Files.isDirectory(current.resolve("Knowledge Base"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required environment variable missing: " + name);
        }
        return value;
    }
}
