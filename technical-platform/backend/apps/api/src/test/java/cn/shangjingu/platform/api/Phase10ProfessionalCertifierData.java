package cn.shangjingu.platform.api;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

/** Independent P010 certifier used to prove that workflow initiators cannot self-certify. */
final class Phase10ProfessionalCertifierData {
    static final String LOGIN = "phase10.certifier";

    private static final UUID EMPLOYEE =
            UUID.fromString("30000000-0000-0000-0000-000000001014");
    private static final UUID APPOINTMENT =
            UUID.fromString("40000000-0000-0000-0000-000000001014");
    private static final UUID USER =
            UUID.fromString("50000000-0000-0000-0000-000000001014");
    private static final UUID IDENTITY =
            UUID.fromString("60000000-0000-0000-0000-000000001014");
    private static final UUID ROLE =
            UUID.fromString("70000000-0000-0000-0000-000000001015");

    private Phase10ProfessionalCertifierData() {}

    static void seed(PostgreSQLContainer<?> postgres, String password) throws Exception {
        String hash = new BCryptPasswordEncoder(12).encode(password);
        try (Connection connection = DriverManager.getConnection(
                        jdbcUrl(postgres), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO org.employee("
                    + "id,tenant_id,employee_no,person_name,employment_status,hire_date,"
                    + "primary_org_id,primary_position_id) VALUES ('"
                    + EMPLOYEE + "','" + Phase10FixtureData.TENANT
                    + "','P10-E005','PHASE10 Professional Certifier','ACTIVE',current_date-30,'"
                    + Phase10FixtureData.CENTER_A + "','" + Phase10FixtureData.POSITION_A + "')");
            statement.execute("INSERT INTO org.employee_position("
                    + "id,tenant_id,employee_id,position_id,org_id,is_primary,"
                    + "effective_start_date,status) VALUES ('"
                    + APPOINTMENT + "','" + Phase10FixtureData.TENANT + "','" + EMPLOYEE
                    + "','" + Phase10FixtureData.POSITION_A + "','" + Phase10FixtureData.CENTER_A
                    + "',true,current_date-30,'ACTIVE')");
            statement.execute("INSERT INTO iam.user_account("
                    + "id,tenant_id,login_name,password_hash,status,mfa_level) VALUES ('"
                    + USER + "','" + Phase10FixtureData.TENANT + "','" + LOGIN + "','" + hash
                    + "','ACTIVE',0)");
            statement.execute("INSERT INTO iam.user_identity("
                    + "id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,"
                    + "position_id,is_primary,effective_start_at) VALUES ('"
                    + IDENTITY + "','" + Phase10FixtureData.TENANT + "','" + USER + "','"
                    + EMPLOYEE + "','EMPLOYEE','PHASE10 Professional Certifier','"
                    + Phase10FixtureData.CENTER_A + "','" + Phase10FixtureData.POSITION_A
                    + "',true,now()-interval '1 day')");
            statement.execute("INSERT INTO iam.role("
                    + "id,tenant_id,role_code,role_name,role_type,data_scope_code,enabled) VALUES ('"
                    + ROLE + "','" + Phase10FixtureData.TENANT
                    + "','P10_CERTIFIER','PHASE10 Professional Certifier','PLATFORM','P10_CENTER',true)");
            statement.execute("INSERT INTO iam.role_permission(tenant_id,role_id,permission_id) "
                    + "SELECT '" + Phase10FixtureData.TENANT + "'::uuid,'" + ROLE
                    + "'::uuid,p.id FROM iam.permission p WHERE p.tenant_id='"
                    + Phase10FixtureData.TENANT + "'::uuid AND p.permission_code IN ("
                    + "'platform.session.read','platform.session.logout',"
                    + "'p010.learning.read','p010.learning.certify') AND NOT p.is_deleted");
            statement.execute("INSERT INTO iam.user_role("
                    + "tenant_id,user_id,identity_id,role_id,effective_start_at,grant_source) VALUES ('"
                    + Phase10FixtureData.TENANT + "','" + USER + "','" + IDENTITY + "','"
                    + ROLE + "',now()-interval '1 day','TEST_ONLY')");
        }
    }

    private static String jdbcUrl(PostgreSQLContainer<?> postgres) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/sjg_oms";
    }
}
