package cn.shangjingu.platform.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.iam.authorization.DataScopeEvaluator;
import cn.shangjingu.platform.iam.authorization.FieldAccessDecision;
import cn.shangjingu.platform.iam.authorization.FieldAccessService;
import cn.shangjingu.platform.iam.authorization.FieldSensitivity;
import cn.shangjingu.platform.iam.infrastructure.JdbcIdentityDirectoryAdapter;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.org.application.OrgDirectoryService;
import cn.shangjingu.platform.org.infrastructure.JdbcOrgDirectoryAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase04AuthorizationIntegrationTest {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000141");
    private static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000000141");
    private static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000000142");
    private static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000000141");
    private static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000000142");
    private static final UUID EMPLOYEE_A = UUID.fromString("30000000-0000-0000-0000-000000000141");
    private static final UUID EMPLOYEE_B = UUID.fromString("30000000-0000-0000-0000-000000000142");
    private static final UUID APPOINTMENT_A = UUID.fromString("40000000-0000-0000-0000-000000000141");
    private static final UUID APPOINTMENT_B = UUID.fromString("40000000-0000-0000-0000-000000000142");
    private static final UUID USER_A = UUID.fromString("50000000-0000-0000-0000-000000000141");
    private static final UUID IDENTITY_A = UUID.fromString("60000000-0000-0000-0000-000000000141");
    private static final UUID IDENTITY_B = UUID.fromString("60000000-0000-0000-0000-000000000142");
    private static final UUID ROLE_CENTER = UUID.fromString("70000000-0000-0000-0000-000000000141");
    private static final UUID ROLE_SELF = UUID.fromString("70000000-0000-0000-0000-000000000142");
    private static final UUID ROLE_UNKNOWN = UUID.fromString("70000000-0000-0000-0000-000000000143");
    private static final UUID PERMISSION_CENTER = UUID.fromString("80000000-0000-0000-0000-000000000141");
    private static final UUID PERMISSION_SELF = UUID.fromString("80000000-0000-0000-0000-000000000142");
    private static final UUID PERMISSION_UNKNOWN = UUID.fromString("80000000-0000-0000-0000-000000000143");
    private static final UUID PERMISSION_CONDITIONAL = UUID.fromString("80000000-0000-0000-0000-000000000144");

    @Test
    void rbacAbacFieldPolicyAndFullRlsContextFailClosed() throws Exception {
        Path root = findRepoRoot();
        String apiPassword = "phase04_c4_" + UUID.randomUUID().toString().replace("-", "");
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
            AuthorizationService authorization = new AuthorizationService(identities, new DataScopeEvaluator());
            FieldAccessService fields = new FieldAccessService(authorization);

            assertEquals(0, jdbc.queryForObject("select count(*) from iam.user_account", Integer.class));
            SessionContext sessionA = new SessionContext(TENANT_A, USER_A, IDENTITY_A, EMPLOYEE_A, APPOINTMENT_A, CENTER_A, POSITION_A, Instant.now());
            SessionContext sessionB = new SessionContext(TENANT_A, USER_A, IDENTITY_B, EMPLOYEE_A, APPOINTMENT_B, CENTER_B, POSITION_B, Instant.now());

            DatabaseSecurityContext databaseContext = new DatabaseSecurityContext(
                    TENANT_A, USER_A, IDENTITY_A, EMPLOYEE_A, APPOINTMENT_A, CENTER_A, POSITION_A);
            transactions.required(databaseContext, () -> {
                assertEquals(TENANT_A.toString(), jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class));
                assertEquals(USER_A.toString(), jdbc.queryForObject("select current_setting('app.user_id', true)", String.class));
                assertEquals(IDENTITY_A.toString(), jdbc.queryForObject("select current_setting('app.identity_id', true)", String.class));
                assertEquals(EMPLOYEE_A.toString(), jdbc.queryForObject("select current_setting('app.employee_id', true)", String.class));
                assertEquals(APPOINTMENT_A.toString(), jdbc.queryForObject("select current_setting('app.appointment_id', true)", String.class));
                assertEquals(CENTER_A.toString(), jdbc.queryForObject("select current_setting('app.org_id', true)", String.class));
                assertEquals(POSITION_A.toString(), jdbc.queryForObject("select current_setting('app.position_id', true)", String.class));
                return null;
            });

            AuthorizationTarget centerAOwn = new AuthorizationTarget(TENANT_A, EMPLOYEE_A, CENTER_A, POSITION_A, EMPLOYEE_A);
            AuthorizationTarget centerBOwn = new AuthorizationTarget(TENANT_A, EMPLOYEE_A, CENTER_B, POSITION_B, EMPLOYEE_A);
            AuthorizationTarget centerAOtherEmployee = new AuthorizationTarget(TENANT_A, EMPLOYEE_B, CENTER_A, POSITION_A, EMPLOYEE_B);

            assertTrue(authorization.authorizeData(sessionA, "platform.test.center", centerAOwn).allowed());
            AuthorizationDecision crossCenter = authorization.authorizeData(sessionA, "platform.test.center", centerBOwn);
            assertFalse(crossCenter.allowed());
            assertEquals(AuthorizationDecision.Reason.DATA_SCOPE_DENIED, crossCenter.reason());

            assertTrue(authorization.authorizeData(sessionA, "platform.test.self", centerAOwn).allowed());
            AuthorizationDecision crossEmployee = authorization.authorizeData(sessionA, "platform.test.self", centerAOtherEmployee);
            assertFalse(crossEmployee.allowed());
            assertEquals(AuthorizationDecision.Reason.DATA_SCOPE_DENIED, crossEmployee.reason());

            assertFalse(authorization.authorizeData(sessionA, "platform.test.unknown-scope", centerAOwn).allowed(),
                    "unknown data-scope expression must fail closed");
            assertFalse(authorization.authorizeAction(sessionA, "platform.test.conditional").allowed(),
                    "unknown non-empty condition_expr must fail closed before ABAC evaluation");
            assertFalse(authorization.authorizeAction(sessionB, "platform.test.center").allowed(),
                    "switching appointment/identity must not carry old role grants into the new identity");

            assertEquals(FieldAccessDecision.Outcome.VISIBLE,
                    fields.decide(sessionA, "platform.test.self", centerAOwn, FieldSensitivity.P2, false).outcome());
            assertEquals(FieldAccessDecision.Outcome.MASKED,
                    fields.decide(sessionA, "platform.test.self", centerAOtherEmployee, FieldSensitivity.P2, false).outcome());
            assertEquals(FieldAccessDecision.Outcome.STEP_UP_REQUIRED,
                    fields.decide(sessionA, "platform.test.self", centerAOwn, FieldSensitivity.P3, false).outcome());
            assertEquals(FieldAccessDecision.Outcome.VISIBLE,
                    fields.decide(sessionA, "platform.test.self", centerAOwn, FieldSensitivity.P3, true).outcome());
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
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_A.toString(),
                        "sjg_tenant_code", "PHASE04_C4",
                        "sjg_tenant_name", "PHASE04 Synthetic C4 Tenant"))
                .cleanDisabled(true).load().migrate();
    }

    private static void seedSyntheticFacts(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES ('" + CENTER_A + "','" + TENANT_A + "','PHASE04_C4_CENTER_A','Synthetic Center A','CENTER','phase04_c4_center_a'::ltree,'ACTIVE'),('" + CENTER_B + "','" + TENANT_A + "','PHASE04_C4_CENTER_B','Synthetic Center B','CENTER','phase04_c4_center_b'::ltree,'ACTIVE')");
            s.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES ('" + POSITION_A + "','" + TENANT_A + "','PHASE04_C4_POS_A','Synthetic Position A','" + CENTER_A + "','ACTIVE'),('" + POSITION_B + "','" + TENANT_A + "','PHASE04_C4_POS_B','Synthetic Position B','" + CENTER_B + "','ACTIVE')");
            s.execute("INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES ('" + EMPLOYEE_A + "','" + TENANT_A + "','PHASE04-C4-E001','Synthetic Alice','ACTIVE',current_date-10,'" + CENTER_A + "','" + POSITION_A + "'),('" + EMPLOYEE_B + "','" + TENANT_A + "','PHASE04-C4-E002','Synthetic Bob','ACTIVE',current_date-10,'" + CENTER_A + "','" + POSITION_A + "')");
            s.execute("INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES ('" + APPOINTMENT_A + "','" + TENANT_A + "','" + EMPLOYEE_A + "','" + POSITION_A + "','" + CENTER_A + "',true,current_date-10,'ACTIVE'),('" + APPOINTMENT_B + "','" + TENANT_A + "','" + EMPLOYEE_A + "','" + POSITION_B + "','" + CENTER_B + "',false,current_date-5,'ACTIVE')");
            s.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('" + USER_A + "','" + TENANT_A + "','phase04.c4.alice','$2a$12$abcdefghijklmnopqrstuuQY0ttWnpsa5hmg.Cp8gY3S8.sW4EQO','ACTIVE',2)");
            s.execute("INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES ('" + IDENTITY_A + "','" + TENANT_A + "','" + USER_A + "','" + EMPLOYEE_A + "','EMPLOYEE','Synthetic C4 Identity A','" + CENTER_A + "','" + POSITION_A + "',true,now()-interval '1 day'),('" + IDENTITY_B + "','" + TENANT_A + "','" + USER_A + "','" + EMPLOYEE_A + "','EMPLOYEE','Synthetic C4 Identity B','" + CENTER_B + "','" + POSITION_B + "',false,now()-interval '1 day')");
            s.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES ('" + TENANT_A + "','PHASE04_C4_CENTER','Center Scope','{\"scope\":\"CENTER\"}'::jsonb,true),('" + TENANT_A + "','PHASE04_C4_SELF','Self Scope','{\"scope\":\"SELF\"}'::jsonb,true),('" + TENANT_A + "','PHASE04_C4_UNKNOWN','Unknown Scope','{\"scope\":\"FUTURE_SCOPE\"}'::jsonb,true)");
            s.execute("INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('" + ROLE_CENTER + "','" + TENANT_A + "','PHASE04_C4_ROLE_CENTER','Center Role','TEST','PHASE04_C4_CENTER',true),('" + ROLE_SELF + "','" + TENANT_A + "','PHASE04_C4_ROLE_SELF','Self Role','TEST','PHASE04_C4_SELF',true),('" + ROLE_UNKNOWN + "','" + TENANT_A + "','PHASE04_C4_ROLE_UNKNOWN','Unknown Role','TEST','PHASE04_C4_UNKNOWN',true)");
            s.execute("INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level) VALUES ('" + PERMISSION_CENTER + "','" + TENANT_A + "','platform.test.center','Center Permission','TEST','READ','NORMAL'),('" + PERMISSION_SELF + "','" + TENANT_A + "','platform.test.self','Self Permission','TEST','READ','NORMAL'),('" + PERMISSION_UNKNOWN + "','" + TENANT_A + "','platform.test.unknown-scope','Unknown Scope Permission','TEST','READ','NORMAL'),('" + PERMISSION_CONDITIONAL + "','" + TENANT_A + "','platform.test.conditional','Conditional Permission','TEST','READ','HIGH')");
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) VALUES ('" + TENANT_A + "','" + ROLE_CENTER + "','" + PERMISSION_CENTER + "'),('" + TENANT_A + "','" + ROLE_SELF + "','" + PERMISSION_SELF + "'),('" + TENANT_A + "','" + ROLE_UNKNOWN + "','" + PERMISSION_UNKNOWN + "')");
            s.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id,condition_expr) VALUES ('" + TENANT_A + "','" + ROLE_CENTER + "','" + PERMISSION_CONDITIONAL + "','{\"requires\":\"UNAPPROVED_SCHEMA\"}'::jsonb)");
            s.execute("INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('" + TENANT_A + "','" + USER_A + "','" + IDENTITY_A + "','" + ROLE_CENTER + "',now()-interval '1 day','TEST_ONLY'),('" + TENANT_A + "','" + USER_A + "','" + IDENTITY_A + "','" + ROLE_SELF + "',now()-interval '1 day','TEST_ONLY'),('" + TENANT_A + "','" + USER_A + "','" + IDENTITY_A + "','" + ROLE_UNKNOWN + "',now()-interval '1 day','TEST_ONLY')");
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
}
