package cn.shangjingu.platform.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class Phase04ApiSecurityIntegrationTest {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final String API_PASSWORD = "c6_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final String AUDIT_PASSWORD = "c6_audit_" + UUID.randomUUID().toString().replace("-", "");
    private static final String LOGIN_SECRET = "Synthetic-C6-Login-7f3d!";

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000341");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000341");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000342");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000341");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000342");
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000341");
    private static final UUID APPOINTMENT_A = UUID.fromString("40000000-0000-0000-0000-000000000341");
    private static final UUID APPOINTMENT_B = UUID.fromString("40000000-0000-0000-0000-000000000342");
    private static final UUID USER = UUID.fromString("50000000-0000-0000-0000-000000000341");
    private static final UUID IDENTITY_A = UUID.fromString("60000000-0000-0000-0000-000000000341");
    private static final UUID IDENTITY_B = UUID.fromString("60000000-0000-0000-0000-000000000342");
    private static final UUID ROLE_A = UUID.fromString("70000000-0000-0000-0000-000000000341");
    private static final UUID ROLE_B = UUID.fromString("70000000-0000-0000-0000-000000000342");
    private static final UUID PERMISSION_READ = UUID.fromString("80000000-0000-0000-0000-000000000341");
    private static final UUID PERMISSION_SWITCH = UUID.fromString("80000000-0000-0000-0000-000000000342");
    private static final UUID PERMISSION_LOGOUT = UUID.fromString("80000000-0000-0000-0000-000000000343");
    private static final UUID PERMISSION_STEP_UP = UUID.fromString("80000000-0000-0000-0000-000000000344");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("bootstrap-" + UUID.randomUUID());
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
        try {
            prepareDatabases();
        } catch (Exception ex) {
            POSTGRES.stop();
            REDIS.stop();
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl(POSTGRES, "sjg_oms"));
        registry.add("spring.datasource.username", () -> "sjg_api_runtime");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("sjg.audit.datasource.url", () -> jdbcUrl(POSTGRES, "sjg_audit"));
        registry.add("sjg.audit.datasource.username", () -> "sjg_audit_writer");
        registry.add("sjg.audit.datasource.password", () -> AUDIT_PASSWORD);
        registry.add("sjg.security.session.access-ttl", () -> "PT2S");
        registry.add("sjg.security.session.refresh-ttl", () -> "PT20S");
        registry.add("sjg.security.step-up.ticket-ttl", () -> "PT5M");
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Test
    void httpSecurityIsOpaqueDenyByDefaultAuditedAndSwitchesAuthorizationContext() throws Exception {
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "tenantCode", "PHASE04_C6",
                                "loginName", "phase04.c6.user",
                                "password", "wrong-synthetic-secret",
                                "identityId", IDENTITY_A))))
                .andExpect(status().isUnauthorized());

        TokenPair initial = login(IDENTITY_A);
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(initial.accessToken())))
                .andExpect(status().isOk());

        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", initial.refreshToken()))))
                .andExpect(status().isOk())
                .andReturn();
        TokenPair rotated = tokens(refresh);

        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(initial.accessToken())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/step-up/tickets")
                        .header("Authorization", bearer(rotated.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "purpose", "platform.test.high-risk",
                                "requiredMfaLevel", 2,
                                "assertion", "synthetic-provider-not-configured"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/not-defined")
                        .header("Authorization", bearer(rotated.accessToken())))
                .andExpect(status().isForbidden());

        MvcResult switchedResult = mockMvc.perform(post("/api/v1/session/switch")
                        .header("Authorization", bearer(rotated.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("identityId", IDENTITY_B))))
                .andExpect(status().isOk())
                .andReturn();
        TokenPair switched = tokens(switchedResult);

        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(rotated.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(switched.accessToken())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(switched.accessToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(switched.accessToken())))
                .andExpect(status().isUnauthorized());

        TokenPair expiring = login(IDENTITY_A);
        Thread.sleep(Duration.ofMillis(2600).toMillis());
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(expiring.accessToken())))
                .andExpect(status().isUnauthorized());

        MvcResult recoveredResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", expiring.refreshToken()))))
                .andExpect(status().isOk())
                .andReturn();
        TokenPair recovered = tokens(recoveredResult);
        mockMvc.perform(get("/api/v1/session")
                        .header("Authorization", bearer(recovered.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(recovered.accessToken())))
                .andExpect(status().isNoContent());

        JdbcTemplate audit = auditJdbc(POSTGRES.getUsername(), POSTGRES.getPassword());
        Integer operationCount = audit.queryForObject("""
                select count(*) from audit.operation_log
                where tenant_id=? and action in (
                    'LOGIN_SUCCESS','SESSION_REFRESH','SESSION_SWITCH','SESSION_LOGOUT','STEP_UP_REJECTED')
                """, Integer.class, TENANT);
        assertTrue(operationCount != null && operationCount >= 7, "critical IAM operations must be append-audited");

        assertTrue(countSecurityEvent(audit, "LOGIN_REJECTED") >= 1);
        assertTrue(countSecurityEvent(audit, "STEP_UP_REJECTED") >= 1);
        assertTrue(countSecurityEvent(audit, "AUTHORIZATION_DENIED") >= 2);

        assertRawCredentialAbsent(audit, initial.accessToken());
        assertRawCredentialAbsent(audit, initial.refreshToken());
        assertRawCredentialAbsent(audit, rotated.accessToken());
        assertRawCredentialAbsent(audit, recovered.refreshToken());

        JdbcTemplate auditWriter = auditJdbc("sjg_audit_writer", AUDIT_PASSWORD);
        assertThrows(DataAccessException.class, () -> auditWriter.update(
                "update audit.operation_log set action=? where tenant_id=?", "MUTATED", TENANT));
    }

    private TokenPair login(UUID identityId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "tenantCode", "PHASE04_C6",
                                "loginName", "phase04.c6.user",
                                "password", LOGIN_SECRET,
                                "identityId", identityId))))
                .andExpect(status().isOk())
                .andReturn();
        return tokens(result);
    }

    private TokenPair tokens(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new TokenPair(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static int countSecurityEvent(JdbcTemplate audit, String eventType) {
        Integer count = audit.queryForObject(
                "select count(*) from audit.security_event where tenant_id=? and event_type=?",
                Integer.class,
                TENANT,
                eventType);
        return count == null ? 0 : count;
    }

    private static void assertRawCredentialAbsent(JdbcTemplate audit, String credential) {
        Integer operationHits = audit.queryForObject(
                "select count(*) from audit.operation_log l where row_to_json(l)::text like ?",
                Integer.class,
                "%" + credential + "%");
        Integer securityHits = audit.queryForObject(
                "select count(*) from audit.security_event e where row_to_json(e)::text like ?",
                Integer.class,
                "%" + credential + "%");
        assertEquals(0, operationHits);
        assertEquals(0, securityHits);
    }

    private static JdbcTemplate auditJdbc(String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl(POSTGRES, "sjg_audit"));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }

    private static void prepareDatabases() throws Exception {
        Path root = findRepoRoot();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true)
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
            statement.execute("CREATE DATABASE sjg_audit");
        }
        Flyway.configure()
                .dataSource(jdbcUrl(POSTGRES, "sjg_oms"), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway/oms"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/oms"))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE04_C6",
                        "sjg_tenant_name", "PHASE04 Synthetic C6 Tenant"))
                .cleanDisabled(true)
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(jdbcUrl(POSTGRES, "sjg_audit"), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway/audit"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/audit"))
                .cleanDisabled(true)
                .load()
                .migrate();
        seedSyntheticFacts();
    }

    private static void seedSyntheticFacts() throws Exception {
        String passwordHash = new BCryptPasswordEncoder(12).encode(LOGIN_SECRET);
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(POSTGRES, "sjg_oms"), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('" + CENTER_A + "','" + TENANT + "','PHASE04_C6_CENTER_A','Synthetic Center A','CENTER','phase04_c6_center_a'::ltree,'ACTIVE'),('" + CENTER_B + "','" + TENANT + "','PHASE04_C6_CENTER_B','Synthetic Center B','CENTER','phase04_c6_center_b'::ltree,'ACTIVE')");
            statement.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('" + POSITION_A + "','" + TENANT + "','PHASE04_C6_POS_A','Synthetic Position A','" + CENTER_A + "','ACTIVE'),('" + POSITION_B + "','" + TENANT + "','PHASE04_C6_POS_B','Synthetic Position B','" + CENTER_B + "','ACTIVE')");
            statement.execute("INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('" + EMPLOYEE + "','" + TENANT + "','PHASE04-C6-E001','Synthetic API User','ACTIVE',current_date-10,'" + CENTER_A + "','" + POSITION_A + "')");
            statement.execute("INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('" + APPOINTMENT_A + "','" + TENANT + "','" + EMPLOYEE + "','" + POSITION_A + "','" + CENTER_A + "',true,current_date-10,'ACTIVE'),('" + APPOINTMENT_B + "','" + TENANT + "','" + EMPLOYEE + "','" + POSITION_B + "','" + CENTER_B + "',false,current_date-5,'ACTIVE')");
            statement.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('" + USER + "','" + TENANT + "','phase04.c6.user','" + passwordHash + "','ACTIVE',0)");
            statement.execute("INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('" + IDENTITY_A + "','" + TENANT + "','" + USER + "','" + EMPLOYEE + "','EMPLOYEE','Synthetic C6 Identity A','" + CENTER_A + "','" + POSITION_A + "',true,now()-interval '1 day'),('" + IDENTITY_B + "','" + TENANT + "','" + USER + "','" + EMPLOYEE + "','EMPLOYEE','Synthetic C6 Identity B','" + CENTER_B + "','" + POSITION_B + "',false,now()-interval '1 day')");
            statement.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES ('" + TENANT + "','PHASE04_C6_CENTER','C6 Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true)");
            statement.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('" + ROLE_A + "','" + TENANT + "','PHASE04_C6_ROLE_A','C6 Identity A Role','PLATFORM','PHASE04_C6_CENTER',true),('" + ROLE_B + "','" + TENANT + "','PHASE04_C6_ROLE_B','C6 Identity B Role','PLATFORM','PHASE04_C6_CENTER',true)");
            statement.execute("INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES ('" + PERMISSION_READ + "','" + TENANT + "','platform.session.read','Read current session','SESSION','READ','NORMAL'),('" + PERMISSION_SWITCH + "','" + TENANT + "','platform.session.switch','Switch current identity','SESSION','SWITCH','HIGH'),('" + PERMISSION_LOGOUT + "','" + TENANT + "','platform.session.logout','Logout current session','SESSION','LOGOUT','NORMAL'),('" + PERMISSION_STEP_UP + "','" + TENANT + "','platform.stepup.issue','Issue Step-Up ticket','SECURITY','STEP_UP','HIGH')");
            statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES ('" + TENANT + "','" + ROLE_A + "','" + PERMISSION_READ + "'),('" + TENANT + "','" + ROLE_A + "','" + PERMISSION_SWITCH + "'),('" + TENANT + "','" + ROLE_A + "','" + PERMISSION_LOGOUT + "'),('" + TENANT + "','" + ROLE_A + "','" + PERMISSION_STEP_UP + "'),('" + TENANT + "','" + ROLE_B + "','" + PERMISSION_LOGOUT + "')");
            statement.execute("INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('" + TENANT + "','" + USER + "','" + IDENTITY_A + "','" + ROLE_A + "',now()-interval '1 day','TEST_ONLY'),('" + TENANT + "','" + USER + "','" + IDENTITY_B + "','" + ROLE_B + "',now()-interval '1 day','TEST_ONLY')");
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
            if (Files.isRegularFile(current.resolve("AGENT.md")) && Files.isDirectory(current.resolve("Knowledge Base"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }
}
