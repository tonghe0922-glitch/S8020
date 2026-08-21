package cn.shangjingu.platform.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.infrastructure.JdbcIdentityDirectoryAdapter;
import cn.shangjingu.platform.iam.session.RedisSessionStore;
import cn.shangjingu.platform.iam.session.SessionPolicy;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.org.application.OrgDirectoryService;
import cn.shangjingu.platform.org.infrastructure.JdbcOrgDirectoryAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class Phase04DirectoryIntegrationTest {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000041");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000042");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000041");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000042");
    private static final UUID EMPLOYEE_A = UUID.fromString("30000000-0000-0000-0000-000000000041");
    private static final UUID APPOINTMENT_A = UUID.fromString("40000000-0000-0000-0000-000000000041");
    private static final UUID APPOINTMENT_B = UUID.fromString("40000000-0000-0000-0000-000000000042");
    private static final UUID USER_A = UUID.fromString("50000000-0000-0000-0000-000000000041");
    private static final UUID IDENTITY_A = UUID.fromString("60000000-0000-0000-0000-000000000041");
    private static final UUID IDENTITY_B = UUID.fromString("60000000-0000-0000-0000-000000000042");
    private static final UUID ROLE_A = UUID.fromString("70000000-0000-0000-0000-000000000041");
    private static final UUID PERMISSION_OK = UUID.fromString("80000000-0000-0000-0000-000000000041");
    private static final UUID PERMISSION_CONDITIONAL = UUID.fromString("80000000-0000-0000-0000-000000000042");
    private static final UUID SCOPE_A = UUID.fromString("90000000-0000-0000-0000-000000000041");

    @Test
    void authoritativeDirectoryUsesPostgresRlsAndFailsClosedOnUnknownGrantCondition() throws Exception {
        Path root = findRepoRoot();
        String apiPassword = "phase04_" + UUID.randomUUID().toString().replace("-", "");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("bootstrap-" + UUID.randomUUID())) {
            postgres.start();
            migrateCluster(root, postgres);
            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement s = c.createStatement()) {
                s.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + apiPassword + "'");
                s.execute("CREATE DATABASE sjg_oms OWNER sjg_owner");
            }
            migrateOms(root, postgres);
            seedSyntheticFacts(postgres);

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(jdbcUrl(postgres, "sjg_oms"));
            dataSource.setUsername("sjg_api_runtime");
            dataSource.setPassword(apiPassword);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TenantTransactionRunner transactions = new TenantTransactionRunner(jdbc, new DataSourceTransactionManager(dataSource));
            OrgDirectoryService orgs = new OrgDirectoryService(new JdbcOrgDirectoryAdapter(jdbc));
            IdentityDirectoryService identities = new IdentityDirectoryService(new JdbcIdentityDirectoryAdapter(jdbc), orgs, transactions);

            Integer withoutContext = jdbc.queryForObject("select count(*) from iam.user_account", Integer.class);
            assertEquals(0, withoutContext, "RLS must not expose tenant rows without transaction tenant context");

            assertEquals(TENANT_A, identities.resolveTenant("PHASE04_A").orElseThrow());
            var account = identities.findAccount(TENANT_A, "phase04.alice").orElseThrow();
            assertEquals(USER_A, account.id());
            assertTrue(account.active());
            assertFalse(account.passwordHash().isBlank());

            var active = identities.activeIdentities(TENANT_A, USER_A);
            assertEquals(2, active.size());
            assertEquals(IDENTITY_A, active.getFirst().id());

            assertFalse(orgs.hasActiveAppointment(TENANT_A, EMPLOYEE_A, CENTER_A, POSITION_A),
                    "direct organization access without tenant transaction context must stay RLS-denied");
            assertTrue(orgs.activeAppointments(TENANT_A, EMPLOYEE_A).isEmpty(),
                    "direct organization list access without tenant transaction context must stay RLS-denied");
            assertTrue(transactions.required(TENANT_A, () ->
                    orgs.hasActiveAppointment(TENANT_A, EMPLOYEE_A, CENTER_A, POSITION_A)));
            assertEquals(APPOINTMENT_A, transactions.required(TENANT_A, () ->
                    orgs.activeAppointments(TENANT_A, EMPLOYEE_A)).getFirst().id());

            var authorization = identities.authorization(TENANT_A, USER_A, IDENTITY_A);
            assertTrue(authorization.hasPermission("platform.test.allowed"));
            assertFalse(authorization.hasPermission("platform.test.conditional"),
                    "unknown non-empty condition_expr must fail closed");
            assertEquals(1, authorization.grants().size());
            assertEquals("PHASE04_CENTER", authorization.grants().getFirst().dataScopeCode());
            assertEquals("{\"scope\": \"CENTER\"}", authorization.grants().getFirst().dataScopeRuleJson());

            assertTrue(identities.findAccount(TENANT_B, "phase04.alice").isEmpty(),
                    "cross-tenant account lookup must not leak tenant A data");
            verifyRedisBackedSessionLifecycle(identities);
        }
    }

    private static void verifyRedisBackedSessionLifecycle(IdentityDirectoryService identities) throws Exception {
        try (GenericContainer<?> redisContainer = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379)) {
            redisContainer.start();
            LettuceConnectionFactory redisFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            redisFactory.afterPropertiesSet();
            try {
                StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
                redis.afterPropertiesSet();
                RedisSessionStore store = new RedisSessionStore(redis);
                SessionService sessions = new SessionService(identities, store, new SessionPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10)));

                var issued = sessions.issue(TENANT_A, USER_A, IDENTITY_A);
                assertEquals(APPOINTMENT_A, issued.context().appointmentId());
                assertEquals(CENTER_A, issued.context().orgId());
                assertTrue(sessions.authenticateAccess(issued.accessToken()).isPresent());
                assertFalse(redis.hasKey("sjg:iam:access:" + issued.accessToken()), "Redis keys must use token digests rather than raw access tokens");

                var refreshed = sessions.refresh(issued.refreshToken());
                assertTrue(sessions.authenticateAccess(issued.accessToken()).isEmpty(), "refresh rotation must revoke the previous access token");
                assertTrue(sessions.authenticateAccess(refreshed.accessToken()).isPresent());
                SessionRejectedException replay = assertThrows(SessionRejectedException.class, () -> sessions.refresh(issued.refreshToken()));
                assertEquals(SessionRejectedException.Reason.REFRESH_REPLAY, replay.reason());

                SessionRejectedException unknownIdentity = assertThrows(SessionRejectedException.class,
                        () -> sessions.switchIdentity(refreshed.accessToken(), UUID.fromString("60000000-0000-0000-0000-000000000099")));
                assertEquals(SessionRejectedException.Reason.IDENTITY_INACTIVE, unknownIdentity.reason());
                assertTrue(sessions.authenticateAccess(refreshed.accessToken()).isPresent(), "failed switch must not revoke the current valid session");

                var switched = sessions.switchIdentity(refreshed.accessToken(), IDENTITY_B);
                assertEquals(IDENTITY_B, switched.context().identityId());
                assertEquals(APPOINTMENT_B, switched.context().appointmentId());
                assertEquals(CENTER_B, switched.context().orgId());
                assertEquals(POSITION_B, switched.context().positionId());
                assertTrue(sessions.authenticateAccess(refreshed.accessToken()).isEmpty(), "successful appointment switch must revoke the old session family");
                assertTrue(sessions.authenticateAccess(switched.accessToken()).isPresent());

                assertTrue(sessions.logout(switched.accessToken()));
                assertTrue(sessions.authenticateAccess(switched.accessToken()).isEmpty());
                assertFalse(sessions.logout(switched.accessToken()));

                SessionService shortAccessSessions = new SessionService(identities, store,
                        new SessionPolicy(Duration.ofMillis(250), Duration.ofSeconds(5)));
                var expiring = shortAccessSessions.issue(TENANT_A, USER_A, IDENTITY_A);
                Thread.sleep(600L);
                assertTrue(shortAccessSessions.authenticateAccess(expiring.accessToken()).isEmpty(), "expired access token must not authenticate");
                var recovered = shortAccessSessions.refresh(expiring.refreshToken());
                assertTrue(shortAccessSessions.authenticateAccess(recovered.accessToken()).isPresent(), "valid refresh token must rotate a session after access expiry");
            } finally {
                redisFactory.destroy();
            }
        }
    }

    private static void migrateCluster(Path root, PostgreSQLContainer<?> postgres) {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true).load().migrate();
    }

    private static void migrateOms(Path root, PostgreSQLContainer<?> postgres) {
        Flyway.configure().dataSource(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/oms"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/oms"))
                .placeholders(Map.of("sjg_tenant_id", TENANT_A.toString(), "sjg_tenant_code", "PHASE04_A", "sjg_tenant_name", "PHASE04 Synthetic Tenant A"))
                .cleanDisabled(true).load().migrate();
    }

    private static void seedSyntheticFacts(PostgreSQLContainer<?> postgres) throws Exception {
        String hash = new BCryptPasswordEncoder(12).encode("phase04-test-password");
        try (Connection c = DriverManager.getConnection(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("INSERT INTO core.tenant(id,tenant_code,tenant_name) VALUES ('" + TENANT_B + "','PHASE04_B','PHASE04 Synthetic Tenant B')");
            s.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('" + CENTER_A + "','" + TENANT_A + "','PHASE04_CENTER_A','Synthetic Center A','CENTER','phase04_center_a'::ltree,'ACTIVE'),('" + CENTER_B + "','" + TENANT_A + "','PHASE04_CENTER_B','Synthetic Center B','CENTER','phase04_center_b'::ltree,'ACTIVE')");
            s.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('" + POSITION_A + "','" + TENANT_A + "','PHASE04_POS_A','Synthetic Position A','" + CENTER_A + "','ACTIVE'),('" + POSITION_B + "','" + TENANT_A + "','PHASE04_POS_B','Synthetic Position B','" + CENTER_B + "','ACTIVE')");
            s.execute("INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('" + EMPLOYEE_A + "','" + TENANT_A + "','PHASE04-E001','Synthetic Alice','ACTIVE',current_date-10,'" + CENTER_A + "','" + POSITION_A + "')");
            s.execute("INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('" + APPOINTMENT_A + "','" + TENANT_A + "','" + EMPLOYEE_A + "','" + POSITION_A + "','" + CENTER_A + "',true,current_date-10,'ACTIVE'),('" + APPOINTMENT_B + "','" + TENANT_A + "','" + EMPLOYEE_A + "','" + POSITION_B + "','" + CENTER_B + "',false,current_date-5,'ACTIVE')");
            s.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('" + USER_A + "','" + TENANT_A + "','phase04.alice','" + hash + "','ACTIVE',2)");
            s.execute("INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('" + IDENTITY_A + "','" + TENANT_A + "','" + USER_A + "','" + EMPLOYEE_A + "','EMPLOYEE','Synthetic Primary Identity','" + CENTER_A + "','" + POSITION_A + "',true,now()-interval '1 day'),('" + IDENTITY_B + "','" + TENANT_A + "','" + USER_A + "','" + EMPLOYEE_A + "','EMPLOYEE','Synthetic Secondary Identity','" + CENTER_B + "','" + POSITION_B + "',false,now()-interval '1 day')");
            s.execute("INSERT INTO iam.data_scope_rule(id,tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES ('" + SCOPE_A + "','" + TENANT_A + "','PHASE04_CENTER','Synthetic Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true)");
            s.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('" + ROLE_A + "','" + TENANT_A + "','PHASE04_ROLE','Synthetic Test Role','TEST','PHASE04_CENTER',true)");
            s.execute("INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES ('" + PERMISSION_OK + "','" + TENANT_A + "','platform.test.allowed','Synthetic Allowed','TEST','READ','NORMAL'),('" + PERMISSION_CONDITIONAL + "','" + TENANT_A + "','platform.test.conditional','Synthetic Conditional','TEST','READ','HIGH')");
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES ('" + TENANT_A + "','" + ROLE_A + "','" + PERMISSION_OK + "')");
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id,condition_expr) VALUES ('" + TENANT_A + "','" + ROLE_A + "','" + PERMISSION_CONDITIONAL + "','{\"requires\":\"UNAPPROVED_SCHEMA\"}'::jsonb)");
            s.execute("INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('" + TENANT_A + "','" + USER_A + "','" + IDENTITY_A + "','" + ROLE_A + "',now()-interval '1 day','TEST_ONLY')");
        }
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
        String url = postgres.getJdbcUrl(); int query = url.indexOf('?'); String suffix = query >= 0 ? url.substring(query) : ""; String base = query >= 0 ? url.substring(0, query) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md")) && Files.isDirectory(current.resolve("Knowledge Base"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
