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

/** Real PHASE-09 / P004 Spring Boot + PostgreSQL16 + Redis browser fixture. */
public final class Phase09P004BrowserBackendFixture {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD = "phase09_p004_api_" + shortId();
    private static final String AUDIT_PASSWORD = "phase09_p004_audit_" + shortId();

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000994");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000994");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000995");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000994");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000995");

    private static final UUID APPLICANT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000994");
    private static final UUID ACTOR1_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000995");
    private static final UUID ACTOR2_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000996");
    private static final UUID TECH_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000997");
    private static final UUID OUT_EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000994");
    private static final UUID ACTOR1_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000995");
    private static final UUID ACTOR2_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000996");
    private static final UUID TECH_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000997");
    private static final UUID OUT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_USER = UUID.fromString("50000000-0000-0000-0000-000000000994");
    private static final UUID ACTOR1_USER = UUID.fromString("50000000-0000-0000-0000-000000000995");
    private static final UUID ACTOR2_USER = UUID.fromString("50000000-0000-0000-0000-000000000996");
    private static final UUID TECH_USER = UUID.fromString("50000000-0000-0000-0000-000000000997");
    private static final UUID OUT_USER = UUID.fromString("50000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000994");
    private static final UUID ACTOR1_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000995");
    private static final UUID ACTOR2_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000996");
    private static final UUID TECH_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000997");
    private static final UUID OUT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000998");

    private static final UUID APPLICANT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000994");
    private static final UUID ACTOR_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000995");
    private static final UUID TECH_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000996");
    private static final UUID OUT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000000997");

    private static final UUID SESSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000991");
    private static final UUID SESSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000992");
    private static final UUID P004_SUBMIT = UUID.fromString("80000000-0000-0000-0000-000000000994");
    private static final UUID P004_READ = UUID.fromString("80000000-0000-0000-0000-000000000995");
    private static final UUID P004_ACT = UUID.fromString("80000000-0000-0000-0000-000000000996");

    static final String ACTOR1_LOGIN = "phase09.p004.actor1";
    static final String ACTOR2_LOGIN = "phase09.p004.actor2";
    static final String TECH_LOGIN = "phase09.p004.tech";
    static final String OUT_LOGIN = "phase09.p004.out";

    private Phase09P004BrowserBackendFixture() {}

    public static void main(String[] args) throws Exception {
        String tenantCode = requiredEnv("PHASE09_P004_TENANT");
        String applicantLogin = requiredEnv("PHASE09_P004_LOGIN");
        String password = requiredEnv("PHASE09_P004_PASSWORD");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres").withUsername("postgres").withPassword("bootstrap-" + UUID.randomUUID());
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        postgres.start();
        redis.start();
        prepareDatabases(postgres, tenantCode, applicantLogin, password);
        ConfigurableApplicationContext context = startApi(postgres, redis);
        writeRuntimeFacts(postgres, redis);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { context.close(); redis.stop(); postgres.stop(); }));
        System.out.println("PHASE09_P004_BROWSER_FIXTURE_READY");
        new CountDownLatch(1).await();
    }

    private static ConfigurableApplicationContext startApi(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) {
        SpringApplication application = new SpringApplication(ApiApplication.class);
        return application.run(
                "--server.port=18084", "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + jdbcUrl(postgres, "sjg_oms"),
                "--spring.datasource.username=sjg_api_runtime", "--spring.datasource.password=" + API_PASSWORD,
                "--spring.data.redis.host=" + redis.getHost(), "--spring.data.redis.port=" + redis.getMappedPort(6379),
                "--sjg.audit.datasource.url=" + jdbcUrl(postgres, "sjg_audit"),
                "--sjg.audit.datasource.username=sjg_audit_writer", "--sjg.audit.datasource.password=" + AUDIT_PASSWORD,
                "--sjg.security.session.access-ttl=PT30M", "--sjg.security.session.refresh-ttl=PT1H");
    }

    private static void prepareDatabases(PostgreSQLContainer<?> postgres, String tenantCode, String applicantLogin, String password) throws Exception {
        Path root = findRepoRoot();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true).load().migrate();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            s.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            s.execute("CREATE DATABASE sjg_oms");
            s.execute("CREATE DATABASE sjg_audit");
        }
        Flyway.configure().dataSource(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/oms"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/oms"))
                .placeholders(Map.of("sjg_tenant_id", TENANT.toString(), "sjg_tenant_code", tenantCode,
                        "sjg_tenant_name", "PHASE09 P004 Browser Tenant"))
                .cleanDisabled(true).load().migrate();
        Flyway.configure().dataSource(jdbcUrl(postgres, "sjg_audit"), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/audit"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/audit"))
                .cleanDisabled(true).load().migrate();
        seed(postgres, applicantLogin, password);
    }

    private static void seed(PostgreSQLContainer<?> postgres, String applicantLogin, String password) throws Exception {
        String hash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection c = DriverManager.getConnection(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES "
                    + "('"+CENTER_A+"','"+TENANT+"','P004_CENTER_A','P004 Center A','CENTER','p004_center_a'::ltree,'ACTIVE'),"
                    + "('"+CENTER_B+"','"+TENANT+"','P004_CENTER_B','P004 Center B','CENTER','p004_center_b'::ltree,'ACTIVE')");
            s.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES "
                    + "('"+POSITION_A+"','"+TENANT+"','P004_POS_A','P004 Position A','"+CENTER_A+"','ACTIVE'),"
                    + "('"+POSITION_B+"','"+TENANT+"','P004_POS_B','P004 Position B','"+CENTER_B+"','ACTIVE')");
            s.execute("INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES "
                    + employee(APPLICANT_EMPLOYEE,"P004-E001","P004 Applicant",CENTER_A,POSITION_A)+","+employee(ACTOR1_EMPLOYEE,"P004-E002","P004 Actor One",CENTER_A,POSITION_A)+","+employee(ACTOR2_EMPLOYEE,"P004-E003","P004 Actor Two",CENTER_A,POSITION_A)+","+employee(TECH_EMPLOYEE,"P004-E004","P004 Tech Monitor",CENTER_A,POSITION_A)+","+employee(OUT_EMPLOYEE,"P004-E005","P004 Cross Center",CENTER_B,POSITION_B));
            s.execute("INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES "
                    + appointment(APPLICANT_APPOINTMENT,APPLICANT_EMPLOYEE,POSITION_A,CENTER_A)+","+appointment(ACTOR1_APPOINTMENT,ACTOR1_EMPLOYEE,POSITION_A,CENTER_A)+","+appointment(ACTOR2_APPOINTMENT,ACTOR2_EMPLOYEE,POSITION_A,CENTER_A)+","+appointment(TECH_APPOINTMENT,TECH_EMPLOYEE,POSITION_A,CENTER_A)+","+appointment(OUT_APPOINTMENT,OUT_EMPLOYEE,POSITION_B,CENTER_B));
            s.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES "
                    + account(APPLICANT_USER,applicantLogin,hash)+","+account(ACTOR1_USER,ACTOR1_LOGIN,hash)+","+account(ACTOR2_USER,ACTOR2_LOGIN,hash)+","+account(TECH_USER,TECH_LOGIN,hash)+","+account(OUT_USER,OUT_LOGIN,hash));
            s.execute("INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES "
                    + identity(APPLICANT_IDENTITY,APPLICANT_USER,APPLICANT_EMPLOYEE,"P004 Applicant",CENTER_A,POSITION_A)+","+identity(ACTOR1_IDENTITY,ACTOR1_USER,ACTOR1_EMPLOYEE,"P004 Actor One",CENTER_A,POSITION_A)+","+identity(ACTOR2_IDENTITY,ACTOR2_USER,ACTOR2_EMPLOYEE,"P004 Actor Two",CENTER_A,POSITION_A)+","+identity(TECH_IDENTITY,TECH_USER,TECH_EMPLOYEE,"P004 Tech",CENTER_A,POSITION_A)+","+identity(OUT_IDENTITY,OUT_USER,OUT_EMPLOYEE,"P004 Out",CENTER_B,POSITION_B));
            s.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES "
                    + "('"+TENANT+"','P004_SELF','P004 Self','{\"scope\":\"SELF\"}'::jsonb,true),"
                    + "('"+TENANT+"','P004_CENTER','P004 Center','{\"scope\":\"CENTER\"}'::jsonb,true)");
            s.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES "
                    + role(APPLICANT_ROLE,"P004_APPLICANT","P004 Applicant","P004_SELF")+","+role(ACTOR_ROLE,"P004_ACTOR","P004 Actor","P004_CENTER")+","+role(TECH_ROLE,"P004_TECH","P004 Tech","P004_CENTER")+","+role(OUT_ROLE,"P004_OUT","P004 Out","P004_CENTER"));
            s.execute("INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES "
                    + permission(SESSION_READ,"platform.session.read","Session read","SESSION","READ","NORMAL")+","+permission(SESSION_LOGOUT,"platform.session.logout","Session logout","SESSION","LOGOUT","NORMAL")+","+permission(P004_SUBMIT,"p004.request.submit","Submit P004","P004_GENERIC_REQUEST","SUBMIT","NORMAL")+","+permission(P004_READ,"p004.request.read","Read P004","P004_GENERIC_REQUEST","READ","NORMAL")+","+permission(P004_ACT,"p004.request.act","Act P004","P004_GENERIC_REQUEST","ACT","HIGH"));
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES "
                    + rp(APPLICANT_ROLE,SESSION_READ)+","+rp(APPLICANT_ROLE,SESSION_LOGOUT)+","+rp(APPLICANT_ROLE,P004_SUBMIT)+","+rp(APPLICANT_ROLE,P004_READ)+","+rp(ACTOR_ROLE,SESSION_READ)+","+rp(ACTOR_ROLE,SESSION_LOGOUT)+","+rp(ACTOR_ROLE,P004_READ)+","+rp(ACTOR_ROLE,P004_ACT)+","+rp(TECH_ROLE,SESSION_READ)+","+rp(TECH_ROLE,SESSION_LOGOUT)+","+rp(TECH_ROLE,P004_READ)+","+rp(OUT_ROLE,SESSION_READ)+","+rp(OUT_ROLE,SESSION_LOGOUT)+","+rp(OUT_ROLE,P004_READ)+","+rp(OUT_ROLE,P004_ACT));
            s.execute("INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES "
                    + ur(APPLICANT_USER,APPLICANT_IDENTITY,APPLICANT_ROLE)+","+ur(ACTOR1_USER,ACTOR1_IDENTITY,ACTOR_ROLE)+","+ur(ACTOR2_USER,ACTOR2_IDENTITY,ACTOR_ROLE)+","+ur(TECH_USER,TECH_IDENTITY,TECH_ROLE)+","+ur(OUT_USER,OUT_IDENTITY,OUT_ROLE));
        }
    }

    private static String employee(UUID id,String no,String name,UUID org,UUID pos){return "('"+id+"','"+TENANT+"','"+no+"','"+name+"','ACTIVE',current_date-30,'"+org+"','"+pos+"')";}
    private static String appointment(UUID id,UUID employee,UUID pos,UUID org){return "('"+id+"','"+TENANT+"','"+employee+"','"+pos+"','"+org+"',true,current_date-30,'ACTIVE')";}
    private static String account(UUID id,String login,String hash){return "('"+id+"','"+TENANT+"','"+login+"','"+hash+"','ACTIVE',0)";}
    private static String identity(UUID id,UUID user,UUID employee,String name,UUID org,UUID pos){return "('"+id+"','"+TENANT+"','"+user+"','"+employee+"','EMPLOYEE','"+name+"','"+org+"','"+pos+"',true,now()-interval '1 day')";}
    private static String role(UUID id,String code,String name,String scope){return "('"+id+"','"+TENANT+"','"+code+"','"+name+"','PLATFORM','"+scope+"',true)";}
    private static String permission(UUID id,String code,String name,String resource,String action,String risk){return "('"+id+"','"+TENANT+"','"+code+"','"+name+"','"+resource+"','"+action+"','"+risk+"')";}
    private static String rp(UUID role,UUID permission){return "('"+TENANT+"','"+role+"','"+permission+"')";}
    private static String ur(UUID user,UUID identity,UUID role){return "('"+TENANT+"','"+user+"','"+identity+"','"+role+"',now()-interval '1 day','TEST_ONLY')";}

    private static void writeRuntimeFacts(PostgreSQLContainer<?> postgres, GenericContainer<?> redis) throws Exception {
        Path output = findRepoRoot().resolve("technical-platform/backend/apps/api/target/phase09-p004-fixture-runtime.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{\n"
                + "  \"postgresContainerId\": \""+postgres.getContainerId()+"\",\n"
                + "  \"redisContainerId\": \""+redis.getContainerId()+"\",\n"
                + "  \"tenantId\": \""+TENANT+"\",\n"
                + "  \"applicantEmployeeId\": \""+APPLICANT_EMPLOYEE+"\",\n"
                + "  \"actor1EmployeeId\": \""+ACTOR1_EMPLOYEE+"\",\n"
                + "  \"actor2EmployeeId\": \""+ACTOR2_EMPLOYEE+"\",\n"
                + "  \"techEmployeeId\": \""+TECH_EMPLOYEE+"\"\n}\n");
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
        return "jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+database;
    }
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("required environment missing: "+name);
        return value;
    }
    private static String shortId(){return UUID.randomUUID().toString().replace("-","").substring(0,16);}
    private static Path findRepoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("mvnw")) && Files.isDirectory(cursor.resolve("technical-platform"))) return cursor;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
