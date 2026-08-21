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

/** Real PHASE-09 / P002 Spring Boot + PostgreSQL + Redis browser fixture. */
public final class Phase09P002BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD = "phase09_p002_api_" + shortId();
    private static final String AUDIT_PASSWORD = "phase09_p002_audit_" + shortId();

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000993");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000993");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000994");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000993");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000994");

    private static final UUID APPLICANT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000993");
    private static final UUID REVIEW1_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000994");
    private static final UUID REVIEW2_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000995");
    private static final UUID REVIEW3_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000996");
    private static final UUID TECH_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000997");
    private static final UUID OUT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000993");
    private static final UUID REVIEW1_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000994");
    private static final UUID REVIEW2_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000995");
    private static final UUID REVIEW3_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000996");
    private static final UUID TECH_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000997");
    private static final UUID OUT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_USER = UUID.fromString("50000000-0000-0000-0000-000000000993");
    private static final UUID REVIEW1_USER = UUID.fromString("50000000-0000-0000-0000-000000000994");
    private static final UUID REVIEW2_USER = UUID.fromString("50000000-0000-0000-0000-000000000995");
    private static final UUID REVIEW3_USER = UUID.fromString("50000000-0000-0000-0000-000000000996");
    private static final UUID TECH_USER = UUID.fromString("50000000-0000-0000-0000-000000000997");
    private static final UUID OUT_USER = UUID.fromString("50000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000993");
    private static final UUID REVIEW1_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000994");
    private static final UUID REVIEW2_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000995");
    private static final UUID REVIEW3_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000996");
    private static final UUID TECH_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000997");
    private static final UUID OUT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000993");
    private static final UUID REVIEW_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000994");
    private static final UUID TECH_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000995");
    private static final UUID OUT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000996");
    private static final UUID REQUESTED_HIGH_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000997");

    private static final UUID PERMISSION_SESSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000991");
    private static final UUID PERMISSION_SESSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000992");
    private static final UUID PERMISSION_SUBMIT = UUID.fromString("80000000-0000-0000-0000-000000000993");
    private static final UUID PERMISSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000994");
    private static final UUID PERMISSION_REVIEW = UUID.fromString("80000000-0000-0000-0000-000000000995");
    private static final UUID PERMISSION_EXECUTE = UUID.fromString("80000000-0000-0000-0000-000000000996");
    private static final UUID PERMISSION_REVOKE = UUID.fromString("80000000-0000-0000-0000-000000000997");
    private static final UUID PERMISSION_HIGH = UUID.fromString("80000000-0000-0000-0000-000000000998");

    static final String REVIEW1_LOGIN = "phase09.p002.review1";
    static final String REVIEW2_LOGIN = "phase09.p002.review2";
    static final String REVIEW3_LOGIN = "phase09.p002.review3";
    static final String TECH_LOGIN = "phase09.p002.tech";
    static final String OUT_LOGIN = "phase09.p002.out";

    private Phase09P002BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE09_P002_TENANT");
        String applicantLogin = requiredEnv("PHASE09_P002_LOGIN");
        String password = requiredEnv("PHASE09_P002_PASSWORD");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, applicantLogin, password);
        ConfigurableApplicationContext context = startApi(postgres, redis);
        writeRuntimeFacts(postgres, redis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            redis.stop();
            postgres.stop();
        }));
        System.out.println("PHASE09_P002_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(
            PostgreSQLContainer<?> postgres,
            GenericContainer<?> redis) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18082",
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
            PostgreSQLContainer<?> postgres,
            String tenantCode,
            String applicantLogin,
            String password) throws Exception {
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
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", tenantCode,
                        "sjg_tenant_name", "PHASE09 P002 Browser Tenant"))
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
        seedSyntheticFacts(postgres, applicantLogin, password);
    }

    private static void seedSyntheticFacts(
            PostgreSQLContainer<?> postgres,
            String applicantLogin,
            String password) throws Exception {
        String passwordHash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES "
                    + "('" + CENTER_A + "','" + TENANT + "','PHASE09_P002_CENTER_A','Synthetic P002 Center A','CENTER','phase09_p002_center_a'::ltree,'ACTIVE'),"
                    + "('" + CENTER_B + "','" + TENANT + "','PHASE09_P002_CENTER_B','Synthetic P002 Center B','CENTER','phase09_p002_center_b'::ltree,'ACTIVE')");
            statement.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES "
                    + "('" + POSITION_A + "','" + TENANT + "','PHASE09_P002_POS_A','Synthetic P002 Position A','" + CENTER_A + "','ACTIVE'),"
                    + "('" + POSITION_B + "','" + TENANT + "','PHASE09_P002_POS_B','Synthetic P002 Position B','" + CENTER_B + "','ACTIVE')");
            statement.execute("INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES "
                    + employee(APPLICANT_EMPLOYEE, "P002-E001", "P002 Applicant", CENTER_A, POSITION_A) + ","
                    + employee(REVIEW1_EMPLOYEE, "P002-E002", "P002 Reviewer One", CENTER_A, POSITION_A) + ","
                    + employee(REVIEW2_EMPLOYEE, "P002-E003", "P002 Reviewer Two", CENTER_A, POSITION_A) + ","
                    + employee(REVIEW3_EMPLOYEE, "P002-E004", "P002 Reviewer Three", CENTER_A, POSITION_A) + ","
                    + employee(TECH_EMPLOYEE, "P002-E005", "P002 Tech Executor", CENTER_A, POSITION_A) + ","
                    + employee(OUT_EMPLOYEE, "P002-E006", "P002 Cross Center Reader", CENTER_B, POSITION_B));
            statement.execute("INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES "
                    + appointment(APPLICANT_APPOINTMENT, APPLICANT_EMPLOYEE, POSITION_A, CENTER_A) + ","
                    + appointment(REVIEW1_APPOINTMENT, REVIEW1_EMPLOYEE, POSITION_A, CENTER_A) + ","
                    + appointment(REVIEW2_APPOINTMENT, REVIEW2_EMPLOYEE, POSITION_A, CENTER_A) + ","
                    + appointment(REVIEW3_APPOINTMENT, REVIEW3_EMPLOYEE, POSITION_A, CENTER_A) + ","
                    + appointment(TECH_APPOINTMENT, TECH_EMPLOYEE, POSITION_A, CENTER_A) + ","
                    + appointment(OUT_APPOINTMENT, OUT_EMPLOYEE, POSITION_B, CENTER_B));
            statement.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES "
                    + account(APPLICANT_USER, applicantLogin, passwordHash) + ","
                    + account(REVIEW1_USER, REVIEW1_LOGIN, passwordHash) + ","
                    + account(REVIEW2_USER, REVIEW2_LOGIN, passwordHash) + ","
                    + account(REVIEW3_USER, REVIEW3_LOGIN, passwordHash) + ","
                    + account(TECH_USER, TECH_LOGIN, passwordHash) + ","
                    + account(OUT_USER, OUT_LOGIN, passwordHash));
            statement.execute("INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES "
                    + identity(APPLICANT_IDENTITY, APPLICANT_USER, APPLICANT_EMPLOYEE, "P002 Applicant Identity", CENTER_A, POSITION_A) + ","
                    + identity(REVIEW1_IDENTITY, REVIEW1_USER, REVIEW1_EMPLOYEE, "P002 Reviewer One", CENTER_A, POSITION_A) + ","
                    + identity(REVIEW2_IDENTITY, REVIEW2_USER, REVIEW2_EMPLOYEE, "P002 Reviewer Two", CENTER_A, POSITION_A) + ","
                    + identity(REVIEW3_IDENTITY, REVIEW3_USER, REVIEW3_EMPLOYEE, "P002 Reviewer Three", CENTER_A, POSITION_A) + ","
                    + identity(TECH_IDENTITY, TECH_USER, TECH_EMPLOYEE, "P002 Tech Identity", CENTER_A, POSITION_A) + ","
                    + identity(OUT_IDENTITY, OUT_USER, OUT_EMPLOYEE, "P002 Out Identity", CENTER_B, POSITION_B));
            statement.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES "
                    + "('" + TENANT + "','PHASE09_P002_SELF','P002 Self Scope','{\"scope\":\"SELF\"}'::jsonb,true),"
                    + "('" + TENANT + "','PHASE09_P002_CENTER','P002 Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true)");
            statement.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES "
                    + role(APPLICANT_ROLE, "PHASE09_P002_APPLICANT", "P002 Applicant Role", "PHASE09_P002_SELF") + ","
                    + role(REVIEW_ROLE, "PHASE09_P002_REVIEW", "P002 Review Role", "PHASE09_P002_CENTER") + ","
                    + role(TECH_ROLE, "PHASE09_P002_TECH", "P002 Tech Role", "PHASE09_P002_CENTER") + ","
                    + role(OUT_ROLE, "PHASE09_P002_OUT", "P002 Cross Center Role", "PHASE09_P002_CENTER") + ","
                    + role(REQUESTED_HIGH_ROLE, "PHASE09_P002_HIGH_TARGET", "P002 High Risk Requested Role", "PHASE09_P002_SELF"));
            statement.execute("INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES "
                    + sessionPermission(PERMISSION_SESSION_READ, "platform.session.read", "Read current session", "READ") + ","
                    + sessionPermission(PERMISSION_SESSION_LOGOUT, "platform.session.logout", "Logout current session", "LOGOUT") + ","
                    + permission(PERMISSION_SUBMIT, "p002.request.submit", "Submit P002 request", "SUBMIT", "NORMAL") + ","
                    + permission(PERMISSION_READ, "p002.request.read", "Read P002 request", "READ", "NORMAL") + ","
                    + permission(PERMISSION_REVIEW, "p002.request.review", "Review P002 request", "REVIEW", "HIGH") + ","
                    + permission(PERMISSION_EXECUTE, "p002.request.execute", "Execute P002 grant", "EXECUTE", "HIGH") + ","
                    + permission(PERMISSION_REVOKE, "p002.request.revoke", "Revoke P002 grant", "REVOKE", "HIGH") + ","
                    + permission(PERMISSION_HIGH, "phase09.p002.synthetic.high", "Synthetic high-risk target permission", "USE", "HIGH"));
            statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES "
                    + rolePermission(APPLICANT_ROLE, PERMISSION_SESSION_READ) + "," + rolePermission(APPLICANT_ROLE, PERMISSION_SESSION_LOGOUT) + ","
                    + rolePermission(APPLICANT_ROLE, PERMISSION_SUBMIT) + "," + rolePermission(APPLICANT_ROLE, PERMISSION_READ) + ","
                    + rolePermission(REVIEW_ROLE, PERMISSION_SESSION_READ) + "," + rolePermission(REVIEW_ROLE, PERMISSION_SESSION_LOGOUT) + ","
                    + rolePermission(REVIEW_ROLE, PERMISSION_READ) + "," + rolePermission(REVIEW_ROLE, PERMISSION_REVIEW) + ","
                    + rolePermission(TECH_ROLE, PERMISSION_SESSION_READ) + "," + rolePermission(TECH_ROLE, PERMISSION_SESSION_LOGOUT) + ","
                    + rolePermission(TECH_ROLE, PERMISSION_READ) + "," + rolePermission(TECH_ROLE, PERMISSION_EXECUTE) + "," + rolePermission(TECH_ROLE, PERMISSION_REVOKE) + ","
                    + rolePermission(OUT_ROLE, PERMISSION_SESSION_READ) + "," + rolePermission(OUT_ROLE, PERMISSION_SESSION_LOGOUT) + ","
                    + rolePermission(OUT_ROLE, PERMISSION_READ) + "," + rolePermission(REQUESTED_HIGH_ROLE, PERMISSION_HIGH));
            statement.execute("INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES "
                    + userRole(APPLICANT_USER, APPLICANT_IDENTITY, APPLICANT_ROLE) + ","
                    + userRole(REVIEW1_USER, REVIEW1_IDENTITY, REVIEW_ROLE) + ","
                    + userRole(REVIEW2_USER, REVIEW2_IDENTITY, REVIEW_ROLE) + ","
                    + userRole(REVIEW3_USER, REVIEW3_IDENTITY, REVIEW_ROLE) + ","
                    + userRole(TECH_USER, TECH_IDENTITY, TECH_ROLE) + ","
                    + userRole(OUT_USER, OUT_IDENTITY, OUT_ROLE));
        }
    }

    private static String employee(UUID id, String no, String name, UUID center, UUID position) {
        return "('" + id + "','" + TENANT + "','" + no + "','" + name + "','ACTIVE',current_date-30,'" + center + "','" + position + "')";
    }

    private static String appointment(UUID id, UUID employee, UUID position, UUID center) {
        return "('" + id + "','" + TENANT + "','" + employee + "','" + position + "','" + center + "',true,current_date-30,'ACTIVE')";
    }

    private static String account(UUID id, String login, String hash) {
        return "('" + id + "','" + TENANT + "','" + login + "','" + hash + "','ACTIVE',0)";
    }

    private static String identity(UUID id, UUID user, UUID employee, String name, UUID center, UUID position) {
        return "('" + id + "','" + TENANT + "','" + user + "','" + employee + "','EMPLOYEE','" + name + "','" + center + "','" + position + "',true,now()-interval '1 day')";
    }

    private static String role(UUID id, String code, String name, String scope) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','PLATFORM','" + scope + "',true)";
    }

    private static String sessionPermission(UUID id, String code, String name, String action) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','SESSION','" + action + "','NORMAL')";
    }

    private static String permission(UUID id, String code, String name, String action, String risk) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','P002_PERMISSION_REQUEST','" + action + "','" + risk + "')";
    }

    private static String rolePermission(UUID role, UUID permission) {
        return "('" + TENANT + "','" + role + "','" + permission + "')";
    }

    private static String userRole(UUID user, UUID identity, UUID role) {
        return "('" + TENANT + "','" + user + "','" + identity + "','" + role + "',now()-interval '1 day','TEST_ONLY')";
    }

    private static void writeRuntimeFacts(
            PostgreSQLContainer<?> postgres,
            GenericContainer<?> redis) throws Exception {
        Path output = findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase09-p002-fixture-runtime.json");
        String json = "{\n"
                + "  \"postgresContainerId\": \"" + postgres.getContainerId() + "\",\n"
                + "  \"redisContainerId\": \"" + redis.getContainerId() + "\",\n"
                + "  \"tenantId\": \"" + TENANT + "\",\n"
                + "  \"applicantEmployeeId\": \"" + APPLICANT_EMPLOYEE + "\",\n"
                + "  \"review1EmployeeId\": \"" + REVIEW1_EMPLOYEE + "\",\n"
                + "  \"review2EmployeeId\": \"" + REVIEW2_EMPLOYEE + "\",\n"
                + "  \"review3EmployeeId\": \"" + REVIEW3_EMPLOYEE + "\",\n"
                + "  \"techEmployeeId\": \"" + TECH_EMPLOYEE + "\",\n"
                + "  \"requestedHighRoleId\": \"" + REQUESTED_HIGH_ROLE + "\"\n"
                + "}\n";
        Files.createDirectories(output.getParent());
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
                    && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("required environment missing: " + name);
        return value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
