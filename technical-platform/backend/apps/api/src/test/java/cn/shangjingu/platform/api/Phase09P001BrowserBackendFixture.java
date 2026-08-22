package cn.shangjingu.platform.api;

import cn.shangjingu.platform.iam.session.SessionService;
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

public final class Phase09P001BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD =
            "phase09_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AUDIT_PASSWORD =
            "phase09_audit_" + UUID.randomUUID().toString().replace("-", "");

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000991");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000991");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000992");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000991");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000992");

    private static final UUID MONITOR_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000991");
    private static final UUID TARGET_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000992");
    private static final UUID OUT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000993");
    private static final UUID MONITOR_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000991");
    private static final UUID TARGET_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000992");
    private static final UUID OUT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000993");

    private static final UUID MONITOR_USER = UUID.fromString("50000000-0000-0000-0000-000000000991");
    private static final UUID TARGET_USER = UUID.fromString("50000000-0000-0000-0000-000000000992");
    private static final UUID OUT_USER = UUID.fromString("50000000-0000-0000-0000-000000000993");
    private static final UUID MONITOR_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000991");
    private static final UUID TARGET_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000992");
    private static final UUID OUT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000993");

    private static final UUID MONITOR_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000991");
    private static final UUID TARGET_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000992");
    private static final UUID OUT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000993");
    private static final UUID PERMISSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000991");
    private static final UUID PERMISSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000992");
    private static final UUID PERMISSION_MONITOR = UUID.fromString("80000000-0000-0000-0000-000000000993");

    private static final String TARGET_LOGIN = "phase09.p001.target";
    private static final String OUT_LOGIN = "phase09.p001.out";

    private Phase09P001BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE09_P001_TENANT");
        String monitorLogin = requiredEnv("PHASE09_P001_LOGIN");
        String password = requiredEnv("PHASE09_P001_PASSWORD");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, monitorLogin, password);
        ConfigurableApplicationContext context = startApi(postgres, redis);
        seedTargetSessions(context);
        writeRuntimeFacts(postgres, redis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            redis.stop();
            postgres.stop();
        }));
        System.out.println("PHASE09_P001_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18081",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl(postgres, "sjg_oms"),
                "--spring.datasource.username=sjg_api_runtime",
                "--spring.datasource.password=" + API_PASSWORD,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--sjg.audit.datasource.url=" + jdbcUrl(postgres, "sjg_audit"),
                "--sjg.audit.datasource.username=sjg_audit_writer",
                "--sjg.audit.datasource.password=" + AUDIT_PASSWORD,
                "--sjg.security.session.access-ttl=PT10M",
                "--sjg.security.session.refresh-ttl=PT20M",
                "--sjg.security.step-up.ticket-ttl=PT5M");
    }

    private static void seedTargetSessions(ConfigurableApplicationContext context) {
        SessionService sessions = context.getBean(SessionService.class);
        sessions.issue(TENANT, TARGET_USER, TARGET_IDENTITY);
        sessions.issue(TENANT, OUT_USER, OUT_IDENTITY);
    }

    private static void prepareDatabases(
            PostgreSQLContainer<?> postgres, String tenantCode, String monitorLogin, String password) throws Exception {
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
                        "PHASE09 P001 Browser Tenant"))
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
        seedSyntheticFacts(postgres, monitorLogin, password);
    }

    private static void seedSyntheticFacts(PostgreSQLContainer<?> postgres, String monitorLogin, String password)
            throws Exception {
        String passwordHash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('"
                            + CENTER_A + "','" + TENANT
                            + "','PHASE09_P001_CENTER_A','Synthetic P001 Center A','CENTER','phase09_p001_center_a'::ltree,'ACTIVE'),('"
                            + CENTER_B + "','" + TENANT
                            + "','PHASE09_P001_CENTER_B','Synthetic P001 Center B','CENTER','phase09_p001_center_b'::ltree,'ACTIVE')");
            statement.execute(
                    "INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('"
                            + POSITION_A + "','" + TENANT + "','PHASE09_P001_POS_A','Synthetic P001 Position A','"
                            + CENTER_A + "','ACTIVE'),('" + POSITION_B + "','" + TENANT
                            + "','PHASE09_P001_POS_B','Synthetic P001 Position B','" + CENTER_B + "','ACTIVE')");
            statement.execute(
                    "INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('"
                            + MONITOR_EMPLOYEE + "','" + TENANT
                            + "','P001-E001','P001 Monitor User','ACTIVE',current_date-10,'" + CENTER_A + "','"
                            + POSITION_A + "'),('" + TARGET_EMPLOYEE + "','" + TENANT
                            + "','P001-E002','P001 Same Center Target','ACTIVE',current_date-10,'" + CENTER_A + "','"
                            + POSITION_A + "'),('" + OUT_EMPLOYEE + "','" + TENANT
                            + "','P001-E003','P001 Cross Center Target','ACTIVE',current_date-10,'" + CENTER_B + "','"
                            + POSITION_B + "')");
            statement.execute(
                    "INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('"
                            + MONITOR_APPOINTMENT + "','" + TENANT + "','" + MONITOR_EMPLOYEE + "','" + POSITION_A
                            + "','" + CENTER_A + "',true,current_date-10,'ACTIVE'),('" + TARGET_APPOINTMENT + "','"
                            + TENANT + "','" + TARGET_EMPLOYEE + "','" + POSITION_A + "','" + CENTER_A
                            + "',true,current_date-10,'ACTIVE'),('" + OUT_APPOINTMENT + "','" + TENANT + "','"
                            + OUT_EMPLOYEE + "','" + POSITION_B + "','" + CENTER_B
                            + "',true,current_date-10,'ACTIVE')");
            statement.execute(
                    "INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('"
                            + MONITOR_USER + "','" + TENANT + "','" + monitorLogin + "','" + passwordHash
                            + "','ACTIVE',0),('" + TARGET_USER + "','" + TENANT + "','" + TARGET_LOGIN + "','"
                            + passwordHash + "','ACTIVE',0),('" + OUT_USER + "','" + TENANT + "','" + OUT_LOGIN + "','"
                            + passwordHash + "','ACTIVE',0)");
            statement.execute(
                    "INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('"
                            + MONITOR_IDENTITY + "','" + TENANT + "','" + MONITOR_USER + "','" + MONITOR_EMPLOYEE
                            + "','EMPLOYEE','P001 Monitor Identity','" + CENTER_A + "','" + POSITION_A
                            + "',true,now()-interval '1 day'),('" + TARGET_IDENTITY + "','" + TENANT + "','"
                            + TARGET_USER + "','" + TARGET_EMPLOYEE + "','EMPLOYEE','P001 Same Center Target','"
                            + CENTER_A + "','" + POSITION_A + "',true,now()-interval '1 day'),('" + OUT_IDENTITY + "','"
                            + TENANT + "','" + OUT_USER + "','" + OUT_EMPLOYEE
                            + "','EMPLOYEE','P001 Cross Center Target','" + CENTER_B + "','" + POSITION_B
                            + "',true,now()-interval '1 day')");
            statement.execute(
                    "INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES ('"
                            + TENANT
                            + "','PHASE09_P001_CENTER','P001 Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true),('"
                            + TENANT + "','PHASE09_P001_SELF','P001 Self Scope','{\"scope\":\"SELF\"}'::jsonb,true)");
            statement.execute(
                    "INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('"
                            + MONITOR_ROLE + "','" + TENANT
                            + "','PHASE09_P001_MONITOR','P001 Monitor Role','PLATFORM','PHASE09_P001_CENTER',true),('"
                            + TARGET_ROLE + "','" + TENANT
                            + "','PHASE09_P001_TARGET','P001 Target Role','PLATFORM','PHASE09_P001_SELF',true),('"
                            + OUT_ROLE + "','" + TENANT
                            + "','PHASE09_P001_OUT','P001 Out Role','PLATFORM','PHASE09_P001_SELF',true)");
            statement.execute(
                    "INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES ('"
                            + PERMISSION_READ + "','" + TENANT
                            + "','platform.session.read','Read current session','SESSION','READ','NORMAL'),('"
                            + PERMISSION_LOGOUT + "','" + TENANT
                            + "','platform.session.logout','Logout current session','SESSION','LOGOUT','NORMAL'),('"
                            + PERMISSION_MONITOR + "','" + TENANT
                            + "','p001.session.monitor','Monitor P001 sessions','SESSION','MONITOR','HIGH')");
            statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES ('" + TENANT
                    + "','" + MONITOR_ROLE + "','" + PERMISSION_READ + "'),('" + TENANT + "','" + MONITOR_ROLE + "','"
                    + PERMISSION_LOGOUT + "'),('" + TENANT + "','" + MONITOR_ROLE + "','" + PERMISSION_MONITOR + "'),('"
                    + TENANT + "','" + TARGET_ROLE + "','" + PERMISSION_READ + "'),('" + TENANT + "','" + TARGET_ROLE
                    + "','" + PERMISSION_LOGOUT + "'),('" + TENANT + "','" + OUT_ROLE + "','" + PERMISSION_READ
                    + "'),('" + TENANT + "','" + OUT_ROLE + "','" + PERMISSION_LOGOUT + "')");
            statement.execute(
                    "INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('"
                            + TENANT + "','" + MONITOR_USER + "','" + MONITOR_IDENTITY + "','" + MONITOR_ROLE
                            + "',now()-interval '1 day','TEST_ONLY'),('" + TENANT + "','" + TARGET_USER + "','"
                            + TARGET_IDENTITY + "','" + TARGET_ROLE + "',now()-interval '1 day','TEST_ONLY'),('"
                            + TENANT + "','" + OUT_USER + "','" + OUT_IDENTITY + "','" + OUT_ROLE
                            + "',now()-interval '1 day','TEST_ONLY')");
        }
    }

    private static void writeRuntimeFacts(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) throws Exception {
        Path output =
                findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase09-p001-fixture-runtime.json");
        String json = "{\n"
                + "  \"postgresContainerId\": \"" + postgres.getContainerId() + "\",\n"
                + "  \"redisContainerId\": \"" + redis.getContainerId() + "\",\n"
                + "  \"tenantId\": \"" + TENANT + "\",\n"
                + "  \"monitorUserId\": \"" + MONITOR_USER + "\",\n"
                + "  \"targetUserId\": \"" + TARGET_USER + "\",\n"
                + "  \"outUserId\": \"" + OUT_USER + "\"\n"
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
