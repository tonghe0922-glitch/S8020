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

/** Real PHASE-09 / P003 Spring Boot + PostgreSQL16 + Redis browser fixture. */
public final class Phase09P003BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD = "phase09_p003_api_" + shortId();
    private static final String AUDIT_PASSWORD = "phase09_p003_audit_" + shortId();

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000983");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000983");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000984");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000983");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000984");

    private static final UUID APPLICANT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000983");
    private static final UUID REVIEW1_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000984");
    private static final UUID REVIEW2_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000985");
    private static final UUID TECH_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000986");
    private static final UUID OUT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000987");

    private static final UUID APPLICANT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000983");
    private static final UUID REVIEW1_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000984");
    private static final UUID REVIEW2_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000985");
    private static final UUID TECH_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000986");
    private static final UUID OUT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000987");

    private static final UUID APPLICANT_USER = UUID.fromString("50000000-0000-0000-0000-000000000983");
    private static final UUID REVIEW1_USER = UUID.fromString("50000000-0000-0000-0000-000000000984");
    private static final UUID REVIEW2_USER = UUID.fromString("50000000-0000-0000-0000-000000000985");
    private static final UUID TECH_USER = UUID.fromString("50000000-0000-0000-0000-000000000986");
    private static final UUID OUT_USER = UUID.fromString("50000000-0000-0000-0000-000000000987");

    private static final UUID APPLICANT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000983");
    private static final UUID REVIEW1_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000984");
    private static final UUID REVIEW2_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000985");
    private static final UUID TECH_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000986");
    private static final UUID OUT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000987");

    private static final UUID APPLICANT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000983");
    private static final UUID REVIEW_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000984");
    private static final UUID TECH_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000985");
    private static final UUID OUT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000986");

    private static final UUID SESSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000981");
    private static final UUID SESSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000982");
    private static final UUID P003_SUBMIT = UUID.fromString("80000000-0000-0000-0000-000000000983");
    private static final UUID P003_READ = UUID.fromString("80000000-0000-0000-0000-000000000984");
    private static final UUID P003_REVIEW = UUID.fromString("80000000-0000-0000-0000-000000000985");
    private static final UUID P003_APPLY = UUID.fromString("80000000-0000-0000-0000-000000000986");

    static final String REVIEW1_LOGIN = "phase09.p003.review1";
    static final String REVIEW2_LOGIN = "phase09.p003.review2";
    static final String TECH_LOGIN = "phase09.p003.tech";
    static final String OUT_LOGIN = "phase09.p003.out";

    private Phase09P003BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE09_P003_TENANT");
        String applicantLogin = requiredEnv("PHASE09_P003_LOGIN");
        String password = requiredEnv("PHASE09_P003_PASSWORD");
        String profileKey = requiredEnv("PHASE09_P003_PROFILE_KEY");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, applicantLogin, password);
        ConfigurableApplicationContext context = startApi(postgres, redis, profileKey);
        writeRuntimeFacts(postgres, redis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            context.close();
            redis.stop();
            postgres.stop();
        }));
        System.out.println("PHASE09_P003_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(
            PostgreSQLContainer<?> postgres, GenericContainer<?> redis, String profileKey) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18083",
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl(postgres, "sjg_oms"),
                "--spring.datasource.username=sjg_api_runtime",
                "--spring.datasource.password=" + API_PASSWORD,
                "--spring.data.redis.host=" + redis.getHost(),
                "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--sjg.audit.datasource.url=" + jdbcUrl(postgres, "sjg_audit"),
                "--sjg.audit.datasource.username=sjg_audit_writer",
                "--sjg.audit.datasource.password=" + AUDIT_PASSWORD,
                "--sjg.security.profile.master-key-base64=" + profileKey,
                "--sjg.security.session.access-ttl=PT30M",
                "--sjg.security.session.refresh-ttl=PT1H");
    }

    private static void prepareDatabases(
            PostgreSQLContainer<?> postgres, String tenantCode, String applicantLogin, String password)
            throws Exception {
        Path root = findRepoRoot();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true)
                .load()
                .migrate();
        try (Connection c = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            s.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            s.execute("CREATE DATABASE sjg_oms");
            s.execute("CREATE DATABASE sjg_audit");
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
                        "PHASE09 P003 Browser Tenant"))
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
        seed(postgres, applicantLogin, password);
    }

    private static void seed(PostgreSQLContainer<?> postgres, String applicantLogin, String password) throws Exception {
        String hash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection c = DriverManager.getConnection(
                        jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES "
                    + "('" + CENTER_A + "','" + TENANT
                    + "','P003_CENTER_A','P003 Center A','CENTER','p003_center_a'::ltree,'ACTIVE'),"
                    + "('" + CENTER_B + "','" + TENANT
                    + "','P003_CENTER_B','P003 Center B','CENTER','p003_center_b'::ltree,'ACTIVE')");
            s.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES "
                    + "('" + POSITION_A + "','" + TENANT + "','P003_POS_A','P003 Position A','" + CENTER_A
                    + "','ACTIVE'),"
                    + "('" + POSITION_B + "','" + TENANT + "','P003_POS_B','P003 Position B','" + CENTER_B
                    + "','ACTIVE')");
            s.execute(
                    "INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES "
                            + employee(APPLICANT_EMPLOYEE, "P003-E001", "P003 Applicant", CENTER_A, POSITION_A) + ","
                            + employee(REVIEW1_EMPLOYEE, "P003-E002", "P003 Reviewer One", CENTER_A, POSITION_A) + ","
                            + employee(REVIEW2_EMPLOYEE, "P003-E003", "P003 Reviewer Two", CENTER_A, POSITION_A) + ","
                            + employee(TECH_EMPLOYEE, "P003-E004", "P003 Tech Applier", CENTER_A, POSITION_A) + ","
                            + employee(OUT_EMPLOYEE, "P003-E005", "P003 Cross Center", CENTER_B, POSITION_B));
            s.execute(
                    "INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES "
                            + appointment(APPLICANT_APPOINTMENT, APPLICANT_EMPLOYEE, POSITION_A, CENTER_A) + ","
                            + appointment(REVIEW1_APPOINTMENT, REVIEW1_EMPLOYEE, POSITION_A, CENTER_A) + ","
                            + appointment(REVIEW2_APPOINTMENT, REVIEW2_EMPLOYEE, POSITION_A, CENTER_A) + ","
                            + appointment(TECH_APPOINTMENT, TECH_EMPLOYEE, POSITION_A, CENTER_A) + ","
                            + appointment(OUT_APPOINTMENT, OUT_EMPLOYEE, POSITION_B, CENTER_B));
            s.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES "
                    + account(APPLICANT_USER, applicantLogin, hash) + "," + account(REVIEW1_USER, REVIEW1_LOGIN, hash)
                    + "," + account(REVIEW2_USER, REVIEW2_LOGIN, hash) + "," + account(TECH_USER, TECH_LOGIN, hash)
                    + "," + account(OUT_USER, OUT_LOGIN, hash));
            s.execute(
                    "INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES "
                            + identity(
                                    APPLICANT_IDENTITY,
                                    APPLICANT_USER,
                                    APPLICANT_EMPLOYEE,
                                    "P003 Applicant",
                                    CENTER_A,
                                    POSITION_A)
                            + ","
                            + identity(
                                    REVIEW1_IDENTITY,
                                    REVIEW1_USER,
                                    REVIEW1_EMPLOYEE,
                                    "P003 Reviewer One",
                                    CENTER_A,
                                    POSITION_A)
                            + ","
                            + identity(
                                    REVIEW2_IDENTITY,
                                    REVIEW2_USER,
                                    REVIEW2_EMPLOYEE,
                                    "P003 Reviewer Two",
                                    CENTER_A,
                                    POSITION_A)
                            + "," + identity(TECH_IDENTITY, TECH_USER, TECH_EMPLOYEE, "P003 Tech", CENTER_A, POSITION_A)
                            + "," + identity(OUT_IDENTITY, OUT_USER, OUT_EMPLOYEE, "P003 Out", CENTER_B, POSITION_B));
            s.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES "
                    + "('" + TENANT + "','P003_SELF','P003 Self','{\"scope\":\"SELF\"}'::jsonb,true),"
                    + "('" + TENANT + "','P003_CENTER','P003 Center','{\"scope\":\"CENTER\"}'::jsonb,true)");
            s.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES "
                    + role(APPLICANT_ROLE, "P003_APPLICANT", "P003 Applicant", "P003_SELF") + ","
                    + role(REVIEW_ROLE, "P003_REVIEW", "P003 Review", "P003_CENTER") + ","
                    + role(TECH_ROLE, "P003_TECH", "P003 Tech", "P003_CENTER") + ","
                    + role(OUT_ROLE, "P003_OUT", "P003 Out", "P003_CENTER"));
            s.execute(
                    "INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES "
                            + permission(
                                    SESSION_READ, "platform.session.read", "Session read", "SESSION", "READ", "NORMAL")
                            + ","
                            + permission(
                                    SESSION_LOGOUT,
                                    "platform.session.logout",
                                    "Session logout",
                                    "SESSION",
                                    "LOGOUT",
                                    "NORMAL")
                            + ","
                            + permission(
                                    P003_SUBMIT,
                                    "p003.change.submit",
                                    "Submit P003",
                                    "P003_PROFILE_CHANGE",
                                    "SUBMIT",
                                    "NORMAL")
                            + ","
                            + permission(
                                    P003_READ, "p003.change.read", "Read P003", "P003_PROFILE_CHANGE", "READ", "NORMAL")
                            + ","
                            + permission(
                                    P003_REVIEW,
                                    "p003.change.review",
                                    "Review P003",
                                    "P003_PROFILE_CHANGE",
                                    "REVIEW",
                                    "HIGH")
                            + ","
                            + permission(
                                    P003_APPLY,
                                    "p003.change.apply",
                                    "Apply P003",
                                    "P003_PROFILE_CHANGE",
                                    "APPLY",
                                    "HIGH"));
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES "
                    + rp(APPLICANT_ROLE, SESSION_READ) + "," + rp(APPLICANT_ROLE, SESSION_LOGOUT) + ","
                    + rp(APPLICANT_ROLE, P003_SUBMIT) + "," + rp(APPLICANT_ROLE, P003_READ) + ","
                    + rp(REVIEW_ROLE, SESSION_READ) + "," + rp(REVIEW_ROLE, SESSION_LOGOUT) + ","
                    + rp(REVIEW_ROLE, P003_READ) + "," + rp(REVIEW_ROLE, P003_REVIEW) + ","
                    + rp(TECH_ROLE, SESSION_READ) + "," + rp(TECH_ROLE, SESSION_LOGOUT) + "," + rp(TECH_ROLE, P003_READ)
                    + "," + rp(TECH_ROLE, P003_APPLY) + "," + rp(OUT_ROLE, SESSION_READ) + ","
                    + rp(OUT_ROLE, SESSION_LOGOUT) + "," + rp(OUT_ROLE, P003_READ) + "," + rp(OUT_ROLE, P003_REVIEW));
            s.execute(
                    "INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES "
                            + ur(APPLICANT_USER, APPLICANT_IDENTITY, APPLICANT_ROLE) + ","
                            + ur(REVIEW1_USER, REVIEW1_IDENTITY, REVIEW_ROLE) + ","
                            + ur(REVIEW2_USER, REVIEW2_IDENTITY, REVIEW_ROLE) + ","
                            + ur(TECH_USER, TECH_IDENTITY, TECH_ROLE) + "," + ur(OUT_USER, OUT_IDENTITY, OUT_ROLE));
        }
    }

    private static String employee(UUID id, String no, String name, UUID center, UUID position) {
        return "('" + id + "','" + TENANT + "','" + no + "','" + name + "','ACTIVE',current_date-30,'" + center + "','"
                + position + "')";
    }

    private static String appointment(UUID id, UUID employee, UUID position, UUID center) {
        return "('" + id + "','" + TENANT + "','" + employee + "','" + position + "','" + center
                + "',true,current_date-30,'ACTIVE')";
    }

    private static String account(UUID id, String login, String hash) {
        return "('" + id + "','" + TENANT + "','" + login + "','" + hash + "','ACTIVE',0)";
    }

    private static String identity(UUID id, UUID user, UUID employee, String name, UUID center, UUID position) {
        return "('" + id + "','" + TENANT + "','" + user + "','" + employee + "','EMPLOYEE','" + name + "','" + center
                + "','" + position + "',true,now()-interval '1 day')";
    }

    private static String role(UUID id, String code, String name, String scope) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','PLATFORM','" + scope + "',true)";
    }

    private static String permission(UUID id, String code, String name, String resource, String action, String risk) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','" + resource + "','" + action + "','"
                + risk + "')";
    }

    private static String rp(UUID role, UUID permission) {
        return "('" + TENANT + "','" + role + "','" + permission + "')";
    }

    private static String ur(UUID user, UUID identity, UUID role) {
        return "('" + TENANT + "','" + user + "','" + identity + "','" + role + "',now()-interval '1 day','TEST_ONLY')";
    }

    private static void writeRuntimeFacts(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) throws Exception {
        Path output =
                findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase09-p003-fixture-runtime.json");
        String json = "{\n  \"postgresContainerId\": \"" + postgres.getContainerId() + "\",\n  \"redisContainerId\": \""
                + redis.getContainerId() + "\",\n  \"tenantId\": \"" + TENANT + "\",\n  \"applicantEmployeeId\": \""
                + APPLICANT_EMPLOYEE + "\",\n  \"review1EmployeeId\": \"" + REVIEW1_EMPLOYEE
                + "\",\n  \"review2EmployeeId\": \"" + REVIEW2_EMPLOYEE + "\",\n  \"techEmployeeId\": \""
                + TECH_EMPLOYEE + "\"\n}\n";
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
        String url = postgres.getJdbcUrl();
        int q = url.indexOf('?');
        String suffix = q >= 0 ? url.substring(q) : "";
        String base = q >= 0 ? url.substring(0, q) : url;
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
