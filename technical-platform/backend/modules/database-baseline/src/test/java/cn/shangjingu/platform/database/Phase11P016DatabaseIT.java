package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase11P016DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001116");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001116");
    private static final UUID POSITION = UUID.fromString("02000000-0000-0000-0000-000000001116");
    private static final UUID EMPLOYEE = UUID.fromString("03000000-0000-0000-0000-000000001116");
    private static final UUID CARE_CASE = UUID.fromString("10000000-0000-0000-0000-000000001116");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase11-p016-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("create database sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        seedCareCase();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void p016PublishedGraphMatchesFrozenReuseContract() throws Exception {
        assertEquals(
                "S01,S02,S03,S04,S05,S06,S07,S08,END",
                scalarString(
                        """
                select string_agg(n.node_code,',' order by n.sort_no)
                from workflow.wf_node n
                join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P016' and v.status='PUBLISHED'
                  and v.checksum='phase11-p016-c0-v1' and not n.is_deleted and not v.is_deleted and not d.is_deleted
                """
                                .formatted(TENANT)));
        assertEquals(
                8L,
                scalarLong(
                        """
                select count(*) from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P016' and v.status='PUBLISHED'
                  and v.checksum='phase11-p016-c0-v1'
                  and t.action_code = any(array[
                    'REGISTER_CARE_CASE','VERIFY_ELIGIBILITY','AUTHORIZE_PRIVACY','APPROVE_CARE',
                    'EXECUTE_BENEFIT','CONFIRM_RECEIPT','RECONCILE','ARCHIVE'])
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """
                                .formatted(TENANT)));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from workflow.wf_form_definition
                where tenant_id='%s' and process_code='P016' and form_code='EMP-P016-F01'
                  and node_code='S01' and enabled and not is_deleted
                """
                                .formatted(TENANT)));
    }

    @Test
    void p016ReusesCanonicalCareCaseAndOnlyAddsSupportingFacts() throws Exception {
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from information_schema.tables
                where table_schema='welfare' and table_name='care_case'
                """));
        assertEquals(
                0L,
                scalarLong(
                        """
                select count(*) from information_schema.tables
                where table_schema='welfare'
                  and table_name in ('care_case_v2','phase11_care_case','p016_case','care_case_shadow')
                """));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from information_schema.tables
                where table_schema='welfare' and table_name='care_case_fact'
                """));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from information_schema.columns
                where table_schema='welfare' and table_name='care_case' and column_name='current_node_code'
                """));
    }

    @Test
    void supportingFactsAreUniqueAndAuditablePerCase() throws Exception {
        execute(
                """
                insert into welfare.care_case_fact(
                  id,tenant_id,care_case_id,fact_type,summary,evidence_reference,actor_employee_id)
                values(gen_random_uuid(),'%s','%s','ELIGIBILITY_VERIFIED','资格已按来源事实核验','ELIG-1','%s')
                """
                        .formatted(TENANT, CARE_CASE, EMPLOYEE));
        SQLException duplicate = assertSqlRejected(
                """
                insert into welfare.care_case_fact(
                  id,tenant_id,care_case_id,fact_type,summary,evidence_reference,actor_employee_id)
                values(gen_random_uuid(),'%s','%s','ELIGIBILITY_VERIFIED','重复资格事实','ELIG-2','%s')
                """
                        .formatted(TENANT, CARE_CASE, EMPLOYEE));
        assertTrue(message(duplicate).contains("uq_p016_case_fact"));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from welfare.care_case_fact
                where tenant_id='%s' and care_case_id='%s' and fact_type='ELIGIBILITY_VERIFIED'
                  and summary<>'' and actor_employee_id is not null and occurred_at is not null
                """
                                .formatted(TENANT, CARE_CASE)));
    }

    @Test
    void p016MigrationRlsAndExactPermissionsAreInstalled() throws Exception {
        assertEquals(1L, scalarLong("select count(*) from flyway_schema_history where success and version='127'"));
        for (String table : List.of("care_case", "care_case_fact")) {
            assertTrue(
                    scalarBoolean("select relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                            + "where n.nspname='welfare' and c.relname='" + table + "'"));
        }
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_policies
                where schemaname='welfare' and tablename='care_case_fact'
                  and policyname='p_tenant_care_case_fact')
                """));
        assertEquals(
                7L,
                scalarLong(
                        """
                select count(*) from iam.permission
                where tenant_id='%s' and permission_code = any(array[
                  'p016.care.create','p016.care.read','p016.care.review','p016.care.execute',
                  'p016.care.confirm','p016.care.reconcile','p016.care.monitor']) and not is_deleted
                """
                                .formatted(TENANT)));
    }

    @Test
    void p016CurrentNodeConstraintRejectsUnknownNode() {
        SQLException invalidNode = assertSqlRejected(
                """
                update welfare.care_case set current_node_code='S99' where id='%s'
                """
                        .formatted(CARE_CASE));
        assertTrue(message(invalidNode).contains("ck_p016_current_node"));
    }

    private static void seedCareCase() throws SQLException {
        execute(
                "insert into org.organization(id,tenant_id,org_code,org_name,org_type,status) values('%s','%s','P016-C','P016 Center','CENTER','ACTIVE')"
                        .formatted(CENTER, TENANT));
        execute(
                "insert into org.position(id,tenant_id,position_code,position_name,org_id,status) values('%s','%s','P016-P','P016 Position','%s','ACTIVE')"
                        .formatted(POSITION, TENANT, CENTER));
        execute(
                """
                insert into org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id)
                values('%s','%s','P016-E','P016 Employee','ACTIVE',date '2026-01-01','%s','%s')
                """
                        .formatted(EMPLOYEE, TENANT, CENTER, POSITION));
        execute(
                """
                insert into welfare.care_case(
                  id,tenant_id,business_no,status,current_node_code,version_no,source_channel,business_date,
                  subject,reason,priority,risk_level,owner_center_id,owner_employee_id,benefit_amount,
                  cost_center_id,currency,employee_event_type,fact_occurred_at,fact_summary,impact_level)
                values('%s','%s','P016-DB-TEST','S02','S02',1,'TEST',date '2026-08-16',
                  'P016 care test','source-backed care','NORMAL','NORMAL','%s','%s',0,
                  'CC-001','CNY','P016_CARE',timestamptz '2026-08-16 00:00:00+00','verified care fact','EMPLOYEE')
                """
                        .formatted(CARE_CASE, TENANT, CENTER, EMPLOYEE));
    }

    private static SQLException assertSqlRejected(String sql) {
        return assertThrows(SQLException.class, () -> {
            try (Connection connection = admin("sjg_oms");
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }

    private static String message(SQLException error) {
        StringBuilder result = new StringBuilder();
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getMessage() != null)
                result.append(current.getMessage()).append(' ');
        }
        return result.toString();
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getBoolean(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement()) {
            assertFalse(s.execute(sql));
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE11_P016_GATE",
                        "sjg_tenant_name", "PHASE-11 P016 Gate Tenant"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String database) {
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
}
