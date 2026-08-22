package cn.shangjingu.platform.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

/** Canonical identities, permissions and qualification seed for the PHASE-10 live gate. */
final class Phase10FixtureData {
    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001010");
    static final UUID CENTER_A = UUID.fromString("10000000-0000-0000-0000-000000001010");
    static final UUID CENTER_B = UUID.fromString("10000000-0000-0000-0000-000000001011");
    static final UUID POSITION_A = UUID.fromString("20000000-0000-0000-0000-000000001010");
    static final UUID POSITION_B = UUID.fromString("20000000-0000-0000-0000-000000001011");

    static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000001010");
    static final UUID MANAGER = UUID.fromString("30000000-0000-0000-0000-000000001011");
    static final UUID TECH = UUID.fromString("30000000-0000-0000-0000-000000001012");
    static final UUID OUT = UUID.fromString("30000000-0000-0000-0000-000000001013");

    static final UUID EMPLOYEE_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000001010");
    static final UUID MANAGER_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000001011");
    static final UUID TECH_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000001012");
    static final UUID OUT_APPOINTMENT = UUID.fromString("40000000-0000-0000-0000-000000001013");

    static final UUID EMPLOYEE_USER = UUID.fromString("50000000-0000-0000-0000-000000001010");
    static final UUID MANAGER_USER = UUID.fromString("50000000-0000-0000-0000-000000001011");
    static final UUID TECH_USER = UUID.fromString("50000000-0000-0000-0000-000000001012");
    static final UUID OUT_USER = UUID.fromString("50000000-0000-0000-0000-000000001013");

    static final UUID EMPLOYEE_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000001010");
    static final UUID MANAGER_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000001011");
    static final UUID TECH_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000001012");
    static final UUID OUT_IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000001013");

    static final UUID EMPLOYEE_ROLE = UUID.fromString("70000000-0000-0000-0000-000000001010");
    static final UUID MANAGER_ROLE = UUID.fromString("70000000-0000-0000-0000-000000001011");
    static final UUID TECH_ROLE = UUID.fromString("70000000-0000-0000-0000-000000001012");
    static final UUID OUT_ROLE = UUID.fromString("70000000-0000-0000-0000-000000001013");
    static final UUID QUALIFICATION_ROLE = UUID.fromString("70000000-0000-0000-0000-000000001014");

    static final String MANAGER_LOGIN = "phase10.manager";
    static final String TECH_LOGIN = "phase10.tech";
    static final String OUT_LOGIN = "phase10.out";
    static final String COURSE_VERSION = "COURSE-P010-E2E";

    static void seed(PostgreSQLContainer<?> postgres, String employeeLogin, String password) throws Exception {
        String hash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(postgres, "sjg_oms"), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            seedOrganization(statement);
            seedEmployees(statement);
            seedAccounts(statement, employeeLogin, hash);
            seedAuthorization(statement);
            seedQualificationBinding(statement);
        }
    }

    private static void seedOrganization(Statement statement) throws Exception {
        statement.execute("INSERT INTO org.organization(id,tenant_id,org_code,org_name,org_type,path,status) VALUES "
                + "('" + CENTER_A + "','" + TENANT
                + "','P10_CENTER_A','PHASE10 Center A','CENTER','p10_center_a'::ltree,'ACTIVE'),"
                + "('" + CENTER_B + "','" + TENANT
                + "','P10_CENTER_B','PHASE10 Center B','CENTER','p10_center_b'::ltree,'ACTIVE')");
        statement.execute("INSERT INTO org.position(id,tenant_id,position_code,position_name,org_id,status) VALUES "
                + "('" + POSITION_A + "','" + TENANT + "','P10_POS_A','PHASE10 Position A','" + CENTER_A
                + "','ACTIVE'),"
                + "('" + POSITION_B + "','" + TENANT + "','P10_POS_B','PHASE10 Position B','" + CENTER_B
                + "','ACTIVE')");
    }

    private static void seedEmployees(Statement statement) throws Exception {
        statement.execute(
                "INSERT INTO org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) VALUES "
                        + employee(EMPLOYEE, "P10-E001", "PHASE10 Employee", CENTER_A, POSITION_A) + ","
                        + employee(MANAGER, "P10-E002", "PHASE10 Manager", CENTER_A, POSITION_A) + ","
                        + employee(TECH, "P10-E003", "PHASE10 Tech Monitor", CENTER_A, POSITION_A) + ","
                        + employee(OUT, "P10-E004", "PHASE10 Cross Center", CENTER_B, POSITION_B));
        statement.execute(
                "INSERT INTO org.employee_position(id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,status) VALUES "
                        + appointment(EMPLOYEE_APPOINTMENT, EMPLOYEE, POSITION_A, CENTER_A) + ","
                        + appointment(MANAGER_APPOINTMENT, MANAGER, POSITION_A, CENTER_A) + ","
                        + appointment(TECH_APPOINTMENT, TECH, POSITION_A, CENTER_A) + ","
                        + appointment(OUT_APPOINTMENT, OUT, POSITION_B, CENTER_B));
    }

    private static void seedAccounts(Statement statement, String employeeLogin, String hash) throws Exception {
        statement.execute("INSERT INTO iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) VALUES "
                + account(EMPLOYEE_USER, employeeLogin, hash) + ","
                + account(MANAGER_USER, MANAGER_LOGIN, hash) + ","
                + account(TECH_USER, TECH_LOGIN, hash) + ","
                + account(OUT_USER, OUT_LOGIN, hash));
        statement.execute(
                "INSERT INTO iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) VALUES "
                        + identity(EMPLOYEE_IDENTITY, EMPLOYEE_USER, EMPLOYEE, "PHASE10 Employee", CENTER_A, POSITION_A)
                        + ","
                        + identity(MANAGER_IDENTITY, MANAGER_USER, MANAGER, "PHASE10 Manager", CENTER_A, POSITION_A)
                        + ","
                        + identity(TECH_IDENTITY, TECH_USER, TECH, "PHASE10 Tech", CENTER_A, POSITION_A) + ","
                        + identity(OUT_IDENTITY, OUT_USER, OUT, "PHASE10 Out", CENTER_B, POSITION_B));
    }

    private static void seedAuthorization(Statement statement) throws Exception {
        statement.execute("INSERT INTO iam.data_scope_rule(tenant_id,scope_code,scope_name,rule_expr,enabled) VALUES "
                + "('" + TENANT + "','P10_SELF','PHASE10 Self','{\"scope\":\"SELF\"}'::jsonb,true),"
                + "('" + TENANT + "','P10_CENTER','PHASE10 Center','{\"scope\":\"CENTER\"}'::jsonb,true)");
        statement.execute(
                "INSERT INTO iam.role(id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES "
                        + role(EMPLOYEE_ROLE, "P10_EMPLOYEE", "PHASE10 Employee", "P10_SELF") + ","
                        + role(MANAGER_ROLE, "P10_MANAGER", "PHASE10 Manager", "P10_CENTER") + ","
                        + role(TECH_ROLE, "P10_TECH", "PHASE10 Tech", "P10_CENTER") + ","
                        + role(OUT_ROLE, "P10_OUT", "PHASE10 Out", "P10_CENTER") + ","
                        + role(QUALIFICATION_ROLE, "P10_QUALIFIED", "PHASE10 Qualified", "P10_SELF"));
        ensureSessionPermissions(statement);
        grant(
                statement,
                EMPLOYEE_ROLE,
                "platform.session.read",
                "platform.session.logout",
                "p006.meeting.read",
                "p006.meeting.action",
                "p007.schedule.read",
                "p007.schedule.change",
                "p008.leave.submit",
                "p008.leave.read",
                "p009.overtime.submit",
                "p009.overtime.read",
                "p010.learning.read",
                "p010.learning.complete",
                "p010.learning.exam");
        String[] managerPermissions = {
            "platform.session.read",
            "platform.session.logout",
            "p006.meeting.create",
            "p006.meeting.read",
            "p006.meeting.manage",
            "p006.meeting.accept",
            "p007.schedule.read",
            "p007.schedule.manage",
            "p007.schedule.change",
            "p007.schedule.review",
            "p008.leave.read",
            "p008.leave.review",
            "p008.leave.manage",
            "p009.overtime.read",
            "p009.overtime.review",
            "p009.overtime.hr",
            "p009.overtime.manage",
            "p010.learning.read",
            "p010.learning.manage",
            "p010.learning.certify"
        };
        grant(statement, MANAGER_ROLE, managerPermissions);
        grant(statement, OUT_ROLE, managerPermissions);
        grant(
                statement,
                TECH_ROLE,
                "platform.session.read",
                "platform.session.logout",
                "p006.meeting.monitor",
                "p007.schedule.monitor",
                "p008.leave.monitor",
                "p009.overtime.monitor",
                "p010.learning.monitor");
        statement.execute(
                "INSERT INTO iam.user_role(tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES "
                        + userRole(EMPLOYEE_USER, EMPLOYEE_IDENTITY, EMPLOYEE_ROLE) + ","
                        + userRole(MANAGER_USER, MANAGER_IDENTITY, MANAGER_ROLE) + ","
                        + userRole(TECH_USER, TECH_IDENTITY, TECH_ROLE) + ","
                        + userRole(OUT_USER, OUT_IDENTITY, OUT_ROLE));
    }

    private static void ensureSessionPermissions(Statement statement) throws Exception {
        statement.execute(
                """
                INSERT INTO iam.permission(
                  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level)
                SELECT gen_random_uuid(),'%s'::uuid,v.code,v.name,'SESSION',v.action,'NORMAL'
                FROM (VALUES
                  ('platform.session.read','Session read','READ'),
                  ('platform.session.logout','Session logout','LOGOUT')
                ) AS v(code,name,action)
                WHERE NOT EXISTS(
                  SELECT 1 FROM iam.permission p
                  WHERE p.tenant_id='%s'::uuid AND p.permission_code=v.code AND NOT p.is_deleted)
                """
                        .formatted(TENANT, TENANT));
    }

    private static void grant(Statement statement, UUID roleId, String... permissionCodes) throws Exception {
        statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) "
                + "SELECT '" + TENANT + "'::uuid,'" + roleId + "'::uuid,p.id FROM iam.permission p "
                + "WHERE p.tenant_id='" + TENANT + "'::uuid AND p.permission_code IN ("
                + sqlStrings(permissionCodes) + ") AND NOT p.is_deleted");
    }

    private static void seedQualificationBinding(Statement statement) throws Exception {
        statement.execute("INSERT INTO learning.qualification_permission_binding("
                + "tenant_id,course_version_id,position_id,role_id,enabled,created_by,updated_by) VALUES ('"
                + TENANT + "','" + COURSE_VERSION + "','" + POSITION_A + "','"
                + QUALIFICATION_ROLE + "',true,'" + MANAGER + "','" + MANAGER + "')");
    }

    private static String employee(UUID id, String no, String name, UUID org, UUID position) {
        return "('" + id + "','" + TENANT + "','" + no + "','" + name + "','ACTIVE',current_date-30,'" + org + "','"
                + position + "')";
    }

    private static String appointment(UUID id, UUID employee, UUID position, UUID org) {
        return "('" + id + "','" + TENANT + "','" + employee + "','" + position + "','" + org
                + "',true,current_date-30,'ACTIVE')";
    }

    private static String account(UUID id, String login, String hash) {
        return "('" + id + "','" + TENANT + "','" + login + "','" + hash + "','ACTIVE',0)";
    }

    private static String identity(UUID id, UUID user, UUID employee, String name, UUID org, UUID position) {
        return "('" + id + "','" + TENANT + "','" + user + "','" + employee
                + "','EMPLOYEE','" + name + "','" + org + "','" + position
                + "',true,now()-interval '1 day')";
    }

    private static String role(UUID id, String code, String name, String scope) {
        return "('" + id + "','" + TENANT + "','" + code + "','" + name + "','PLATFORM','" + scope + "',true)";
    }

    private static String userRole(UUID user, UUID identity, UUID role) {
        return "('" + TENANT + "','" + user + "','" + identity + "','" + role + "',now()-interval '1 day','TEST_ONLY')";
    }

    private static String sqlStrings(String... values) {
        return Arrays.stream(values)
                .map(value -> "'" + value.replace("'", "''") + "'")
                .collect(Collectors.joining(","));
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    private Phase10FixtureData() {}
}
