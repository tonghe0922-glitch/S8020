package cn.shangjingu.platform.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.infrastructure.JdbcIdentityDirectoryAdapter;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.stepup.FailClosedMfaCapabilityProvider;
import cn.shangjingu.platform.iam.stepup.MfaCapabilityProvider;
import cn.shangjingu.platform.iam.stepup.RedisStepUpTicketStore;
import cn.shangjingu.platform.iam.stepup.StepUpAuditEvent;
import cn.shangjingu.platform.iam.stepup.StepUpAuditSink;
import cn.shangjingu.platform.iam.stepup.StepUpPolicy;
import cn.shangjingu.platform.iam.stepup.StepUpRejectedException;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import cn.shangjingu.platform.org.application.OrgDirectoryService;
import cn.shangjingu.platform.org.infrastructure.JdbcOrgDirectoryAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class Phase04StepUpIntegrationTest {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000241");
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000000241");
    private static final UUID POSITION = UUID.fromString("20000000-0000-0000-0000-000000000241");
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000241");
    private static final UUID APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000000241");
    private static final UUID USER = UUID.fromString("50000000-0000-0000-0000-000000000241");
    private static final UUID IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000241");

    @Test
    void stepUpTicketsAreMfaGatedIdentityBoundShortLivedOneTimeAndAuditRequired() throws Exception {
        Path root = findRepoRoot();
        String apiPassword = "phase04_c5_" + UUID.randomUUID().toString().replace("-", "");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                        .withDatabaseName("postgres")
                        .withUsername("postgres")
                        .withPassword("bootstrap-" + UUID.randomUUID());
                GenericContainer<?> redisContainer = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379)) {
            postgres.start();
            redisContainer.start();
            migrateCluster(root, postgres);
            try (Connection c = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
            TenantTransactionRunner transactions =
                    new TenantTransactionRunner(jdbc, new DataSourceTransactionManager(dataSource));
            OrgDirectoryService orgs = new OrgDirectoryService(new JdbcOrgDirectoryAdapter(jdbc));
            IdentityDirectoryService identities =
                    new IdentityDirectoryService(new JdbcIdentityDirectoryAdapter(jdbc), orgs, transactions);

            LettuceConnectionFactory redisFactory =
                    new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
            redisFactory.afterPropertiesSet();
            try {
                StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
                redis.afterPropertiesSet();
                RedisStepUpTicketStore store = new RedisStepUpTicketStore(redis);
                SessionContext subject = new SessionContext(
                        TENANT, USER, IDENTITY, EMPLOYEE, APPOINTMENT, CENTER, POSITION, Instant.now());
                List<StepUpAuditEvent> events = new ArrayList<>();
                StepUpAuditSink audit = events::add;

                StepUpService unavailable = new StepUpService(
                        identities,
                        store,
                        new StepUpPolicy(Duration.ofSeconds(5)),
                        new FailClosedMfaCapabilityProvider(),
                        audit);
                StepUpRejectedException unavailableResult = assertThrows(
                        StepUpRejectedException.class,
                        () -> unavailable.issue(subject, "platform.test.high-risk", 2, "synthetic-assertion"));
                assertEquals(StepUpRejectedException.Reason.MFA_VERIFICATION_FAILED, unavailableResult.reason());
                assertEquals("STEP_UP_REJECTED", events.getLast().eventType());
                assertEquals("MFA_VERIFICATION_FAILED", events.getLast().outcome());

                MfaCapabilityProvider syntheticProvider = (context, enrolled, required, assertion) ->
                        enrolled >= required && "synthetic-approved".equals(assertion);
                StepUpService auditUnavailable = new StepUpService(
                        identities, store, new StepUpPolicy(Duration.ofSeconds(5)), syntheticProvider, event -> {
                            throw new IllegalStateException("synthetic audit outage");
                        });
                StepUpRejectedException auditFailure = assertThrows(
                        StepUpRejectedException.class,
                        () -> auditUnavailable.issue(subject, "platform.test.audit-required", 1, "synthetic-approved"));
                assertEquals(StepUpRejectedException.Reason.AUDIT_UNAVAILABLE, auditFailure.reason());
                assertTrue(
                        redis.keys("sjg:iam:stepup:ticket:*").isEmpty(),
                        "ticket creation must be compensated when critical audit persistence fails");

                StepUpService stepUp = new StepUpService(
                        identities, store, new StepUpPolicy(Duration.ofSeconds(5)), syntheticProvider, audit);

                StepUpRejectedException insufficient = assertThrows(
                        StepUpRejectedException.class,
                        () -> stepUp.issue(subject, "platform.test.high-risk", 3, "synthetic-approved"));
                assertEquals(StepUpRejectedException.Reason.MFA_LEVEL_INSUFFICIENT, insufficient.reason());
                StepUpRejectedException badAssertion = assertThrows(
                        StepUpRejectedException.class,
                        () -> stepUp.issue(subject, "platform.test.high-risk", 2, "synthetic-denied"));
                assertEquals(StepUpRejectedException.Reason.MFA_VERIFICATION_FAILED, badAssertion.reason());

                var ticket = stepUp.issue(subject, "platform.test.high-risk", 2, "synthetic-approved");
                assertFalse(
                        redis.hasKey("sjg:iam:stepup:ticket:" + ticket.ticket()),
                        "Redis must key Step-Up state by SHA-256 digest, never by raw ticket");
                assertEquals("STEP_UP_ISSUED", events.getLast().eventType());

                SessionContext wrongIdentity = new SessionContext(
                        TENANT,
                        USER,
                        UUID.fromString("60000000-0000-0000-0000-000000000299"),
                        EMPLOYEE,
                        APPOINTMENT,
                        CENTER,
                        POSITION,
                        Instant.now());
                StepUpRejectedException mismatch = assertThrows(
                        StepUpRejectedException.class,
                        () -> stepUp.requireAndConsume(ticket.ticket(), wrongIdentity, "platform.test.high-risk"));
                assertEquals(StepUpRejectedException.Reason.TICKET_CONTEXT_MISMATCH, mismatch.reason());

                stepUp.requireAndConsume(ticket.ticket(), subject, "platform.test.high-risk");
                assertEquals("STEP_UP_CONSUMED", events.getLast().eventType());
                StepUpRejectedException replay = assertThrows(
                        StepUpRejectedException.class,
                        () -> stepUp.requireAndConsume(ticket.ticket(), subject, "platform.test.high-risk"));
                assertEquals(StepUpRejectedException.Reason.TICKET_REPLAYED, replay.reason());

                StepUpService shortLived = new StepUpService(
                        identities, store, new StepUpPolicy(Duration.ofMillis(250)), syntheticProvider, audit);
                var expiring = shortLived.issue(subject, "platform.test.expiring", 1, "synthetic-approved");
                Thread.sleep(600L);
                StepUpRejectedException expired = assertThrows(
                        StepUpRejectedException.class,
                        () -> shortLived.requireAndConsume(expiring.ticket(), subject, "platform.test.expiring"));
                assertEquals(StepUpRejectedException.Reason.TICKET_MISSING_OR_EXPIRED, expired.reason());
            } finally {
                redisFactory.destroy();
            }
        }
    }

    private static void migrateCluster(Path root, PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static void migrateOms(Path root, PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword())
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway/oms"),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays/oms"))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE04_C5",
                        "sjg_tenant_name", "PHASE04 Synthetic C5 Tenant"))
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static void seedSyntheticFacts(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('"
                    + CENTER + "','" + TENANT
                    + "','PHASE04_C5_CENTER','Synthetic Center','CENTER','phase04_c5_center'::ltree,'ACTIVE')");
            s.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('"
                    + POSITION + "','" + TENANT + "','PHASE04_C5_POS','Synthetic Position','" + CENTER + "','ACTIVE')");
            s.execute(
                    "INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('"
                            + EMPLOYEE + "','" + TENANT
                            + "','PHASE04-C5-E001','Synthetic StepUp User','ACTIVE',current_date-10,'" + CENTER + "','"
                            + POSITION + "')");
            s.execute(
                    "INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('"
                            + APPOINTMENT + "','" + TENANT + "','" + EMPLOYEE + "','" + POSITION + "','" + CENTER
                            + "',true,current_date-10,'ACTIVE')");
            s.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('"
                    + USER + "','" + TENANT
                    + "','phase04.c5.user','$2a$12$abcdefghijklmnopqrstuuQY0ttWnpsa5hmg.Cp8gY3S8.sW4EQO','ACTIVE',2)");
            s.execute(
                    "INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('"
                            + IDENTITY + "','" + TENANT + "','" + USER + "','" + EMPLOYEE
                            + "','EMPLOYEE','Synthetic C5 Identity','" + CENTER + "','" + POSITION
                            + "',true,now()-interval '1 day')");
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
                    && Files.isDirectory(current.resolve("Knowledge Base"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
