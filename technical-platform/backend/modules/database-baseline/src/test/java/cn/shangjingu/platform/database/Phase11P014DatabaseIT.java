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

class Phase11P014DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001114");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001114");
    private static final UUID POSITION = UUID.fromString("02000000-0000-0000-0000-000000001114");
    private static final UUID SUBJECT = UUID.fromString("03000000-0000-0000-0000-000000001114");
    private static final UUID INVESTIGATOR = UUID.fromString("04000000-0000-0000-0000-000000001114");
    private static final UUID DECIDER = UUID.fromString("05000000-0000-0000-0000-000000001114");
    private static final UUID DISCIPLINE = UUID.fromString("10000000-0000-0000-0000-000000001114");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase11-p014-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("create database sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        seedFacts();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void p014PublishedGraphMatchesFrozenContract() throws Exception {
        assertEquals(
                "S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,S11,S12,END",
                scalarString(
                        """
                        select string_agg(n.node_code,',' order by n.sort_no)
                        from workflow.wf_node n
                        join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                        join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where d.tenant_id='%s' and d.process_code='P014'
                          and v.status='PUBLISHED' and v.checksum='phase11-p014-c0-v2'
                          and not n.is_deleted and not v.is_deleted and not d.is_deleted
                        """
                                .formatted(TENANT)));
        assertEquals(
                12L,
                scalarLong(
                        """
                select count(*) from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P014'
                  and v.status='PUBLISHED' and v.checksum='phase11-p014-c0-v2'
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """
                                .formatted(TENANT)));
        assertEquals(
                12L,
                scalarLong(
                        """
                select count(*) from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P014'
                  and v.checksum='phase11-p014-c0-v2'
                  and t.action_code = any(array[
                    'REGISTER_LEAD','APPLY_SAFETY_MEASURE','COMPLETE_INVESTIGATION','SUBMIT_DEFENSE',
                    'COMPLETE_RESPONSIBILITY_REVIEW','APPROVE_DECISION','ACKNOWLEDGE_SERVICE',
                    'EXECUTE_IMPACTS','RESOLVE_APPEAL','CLOSE_CORE_CASE','COMPLETE_OBSERVATION','ARCHIVE'])
                  and not t.is_deleted
                """
                                .formatted(TENANT)));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from workflow.wf_form_definition
                where tenant_id='%s' and process_code='P014' and form_code='CTR-P014-F01'
                  and node_code='S01' and enabled and not is_deleted
                """
                                .formatted(TENANT)));
    }

    @Test
    void oneSourceFactCreatesAtMostOneDisciplineCase() {
        SQLException duplicate = assertSqlRejected(
                """
                insert into reward.discipline_case(
                  id,tenant_id,business_no,status,current_node_code,subject,reason,
                  owner_center_id,owner_employee_id,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,source_fact_key,source_type)
                values(gen_random_uuid(),'%s','P014-DUP','线索登记','S01','duplicate','duplicate',
                  '%s','%s','P014_DISCIPLINE',now(),'duplicate','EMPLOYEE','P014-SOURCE-1','INTERNAL')
                """
                        .formatted(TENANT, CENTER, SUBJECT));
        assertTrue(message(duplicate).contains("uq_p014_source_fact"));
    }

    @Test
    void databaseRejectsInvestigationDecisionAndAppealRoleConflicts() throws SQLException {
        SQLException selfInvestigation = assertSqlRejected(
                """
                update reward.discipline_case
                   set investigator_employee_id='%s'
                 where id='%s'
                """
                        .formatted(SUBJECT, DISCIPLINE));
        assertTrue(message(selfInvestigation).contains("ck_p014_investigator_sod"));

        SQLException selfDecision = assertSqlRejected(
                """
                update reward.discipline_case
                   set decision_employee_id='%s'
                 where id='%s'
                """
                        .formatted(SUBJECT, DISCIPLINE));
        assertTrue(message(selfDecision).contains("ck_p014_decision_sod"));

        execute(
                """
                update reward.discipline_case
                   set decision_employee_id='%s'
                 where id='%s'
                """
                        .formatted(DECIDER, DISCIPLINE));
        SQLException originalDecisionReviewer = assertSqlRejected(
                """
                update reward.discipline_case
                   set appeal_reviewer_employee_id='%s'
                 where id='%s'
                """
                        .formatted(DECIDER, DISCIPLINE));
        assertTrue(message(originalDecisionReviewer).contains("ck_p014_appeal_reviewer_sod"));

        SQLException subjectReviewer = assertSqlRejected(
                """
                update reward.discipline_case
                   set appeal_reviewer_employee_id='%s'
                 where id='%s'
                """
                        .formatted(SUBJECT, DISCIPLINE));
        assertTrue(message(subjectReviewer).contains("ck_p014_appeal_reviewer_sod"));
    }

    @Test
    void ordinaryInternalCaseDoesNotRequireCrmButCrmLinkCannotLeakToInternalSource() throws Exception {
        execute(
                """
                insert into reward.discipline_case(
                  id,tenant_id,business_no,status,current_node_code,subject,reason,
                  owner_center_id,owner_employee_id,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,source_fact_key,source_type)
                values(gen_random_uuid(),'%s','P014-INTERNAL-2','线索登记','S01','internal','internal',
                  '%s','%s','P014_DISCIPLINE',now(),'internal fact','EMPLOYEE','P014-SOURCE-2','INTERNAL')
                """
                        .formatted(TENANT, CENTER, SUBJECT));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from reward.discipline_case
                where tenant_id='%s' and business_no='P014-INTERNAL-2'
                  and customer_id is null and customer_name is null
                """
                                .formatted(TENANT)));

        SQLException leakedCrm = assertSqlRejected(
                """
                insert into reward.discipline_case(
                  id,tenant_id,business_no,status,current_node_code,subject,reason,
                  owner_center_id,owner_employee_id,customer_id,customer_name,employee_event_type,
                  fact_occurred_at,fact_summary,impact_level,source_fact_key,source_type)
                values(gen_random_uuid(),'%s','P014-CRM-INVALID','线索登记','S01','invalid','invalid',
                  '%s','%s','CRM-1','Customer','P014_DISCIPLINE',now(),'invalid fact','EMPLOYEE',
                  'P014-SOURCE-CRM-INVALID','INTERNAL')
                """
                        .formatted(TENANT, CENTER, SUBJECT));
        assertTrue(message(leakedCrm).contains("ck_p014_customer_link_scope"));
    }

    @Test
    void p014MigrationAndRlsAreInstalled() throws Exception {
        assertEquals(1L, scalarLong("select count(*) from flyway_schema_history where success and version='125'"));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_policies
                where schemaname='reward' and tablename='discipline_case'
                  and policyname='p_tenant_discipline_case')
                """));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_indexes
                where schemaname='reward' and tablename='discipline_case'
                  and indexname='uq_p014_source_fact')
                """));
    }

    private static void seedFacts() throws SQLException {
        execute(
                """
                insert into org.organization(id,tenant_id,org_code,org_name,org_type,status)
                values('%s','%s','PHASE11-P014-CENTER','PHASE-11 P014 Center','CENTER','ACTIVE')
                """
                        .formatted(CENTER, TENANT));
        execute(
                """
                insert into org.position(id,tenant_id,position_code,position_name,org_id,status)
                values('%s','%s','P014-POS','P014 Position','%s','ACTIVE')
                """
                        .formatted(POSITION, TENANT, CENTER));
        seedEmployee(SUBJECT, "P014-SUBJECT", "P014 Subject");
        seedEmployee(INVESTIGATOR, "P014-INVESTIGATOR", "P014 Investigator");
        seedEmployee(DECIDER, "P014-DECIDER", "P014 Decider");
        execute(
                """
                insert into reward.discipline_case(
                  id,tenant_id,business_no,status,current_node_code,version_no,business_date,
                  subject,reason,priority,risk_level,owner_center_id,owner_employee_id,
                  employee_event_type,fact_occurred_at,fact_summary,impact_level,
                  source_fact_key,source_type,content_version,period_no)
                values('%s','%s','P014-DB-TEST','调查','S03',2,date '2026-08-16',
                  'P014 discipline test','discipline','NORMAL','HIGH','%s','%s',
                  'P014_DISCIPLINE',timestamptz '2026-08-16 00:00:00+00','discipline fact','EMPLOYEE',
                  'P014-SOURCE-1','INTERNAL','P014-CONTENT-V1','2026-Q3')
                """
                        .formatted(DISCIPLINE, TENANT, CENTER, SUBJECT));
        execute(
                """
                update reward.discipline_case set investigator_employee_id='%s'
                where id='%s'
                """
                        .formatted(INVESTIGATOR, DISCIPLINE));
    }

    private static void seedEmployee(UUID id, String employeeNo, String name) throws SQLException {
        execute(
                """
                insert into org.employee(
                  id,tenant_id,employee_no,person_name,employment_status,hire_date,
                  primary_org_id,primary_position_id)
                values('%s','%s','%s','%s','ACTIVE',date '2026-01-01','%s','%s')
                """
                        .formatted(id, TENANT, employeeNo, name, CENTER, POSITION));
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
            if (current.getMessage() != null) {
                result.append(current.getMessage()).append(' ');
            }
        }
        return result.toString();
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement()) {
            assertFalse(statement.execute(sql));
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
                        "sjg_tenant_code", "PHASE11_P014_GATE",
                        "sjg_tenant_name", "PHASE-11 P014 Gate Tenant"))
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
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
