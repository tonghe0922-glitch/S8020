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

class Phase11P012DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001112");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001112");
    private static final UUID EMPLOYEE = UUID.fromString("02000000-0000-0000-0000-000000001112");
    private static final UUID CURRENT_POSITION = UUID.fromString("03000000-0000-0000-0000-000000001112");
    private static final UUID TARGET_POSITION = UUID.fromString("04000000-0000-0000-0000-000000001112");
    private static final UUID CYCLE = UUID.fromString("10000000-0000-0000-0000-000000001112");
    private static final UUID PROMOTION = UUID.fromString("20000000-0000-0000-0000-000000001112");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase11-p012-" + UUID.randomUUID());
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
    void p012PublishedGraphMatchesFrozenContract() throws Exception {
        assertEquals(
                "S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,END",
                scalarString(
                        """
                        select string_agg(n.node_code,',' order by n.sort_no)
                        from workflow.wf_node n
                        join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                        join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where d.tenant_id='%s' and d.process_code='P012'
                          and v.status='PUBLISHED' and v.checksum='phase11-p012-c0-v1'
                          and not n.is_deleted and not v.is_deleted and not d.is_deleted
                        """
                                .formatted(TENANT)));
        assertEquals(
                10L,
                scalarLong(
                        """
                select count(*)
                from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P012'
                  and v.status='PUBLISHED' and v.checksum='phase11-p012-c0-v1'
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """
                                .formatted(TENANT)));
    }

    @Test
    void onePromotionCreatesAtMostOneAppointmentEffect() throws Exception {
        execute(
                """
                insert into org.employee_position(
                  id,tenant_id,employee_id,position_id,org_id,is_primary,
                  effective_start_date,status,source_promotion_request_id)
                values(gen_random_uuid(),'%s','%s','%s','%s',true,date '2026-09-01','ACTIVE','%s')
                """
                        .formatted(TENANT, EMPLOYEE, TARGET_POSITION, CENTER, PROMOTION));
        SQLException duplicate = assertSqlRejected(
                """
                insert into org.employee_position(
                  id,tenant_id,employee_id,position_id,org_id,is_primary,
                  effective_start_date,status,source_promotion_request_id)
                values(gen_random_uuid(),'%s','%s','%s','%s',true,date '2026-09-01','ACTIVE','%s')
                """
                        .formatted(TENANT, EMPLOYEE, TARGET_POSITION, CENTER, PROMOTION));
        assertTrue(message(duplicate).contains("uq_employee_position_promotion_effect"));
        assertEquals(
                1L,
                scalarLong(
                        """
                select count(*) from org.employee_position
                where tenant_id='%s' and source_promotion_request_id='%s'
                """
                                .formatted(TENANT, PROMOTION)));
    }

    @Test
    void promotionReviewAndNodeConstraintsFailClosed() {
        SQLException score = assertSqlRejected(
                "update hr.promotion_request set weighted_review_score=1001 where id='" + PROMOTION + "'");
        assertTrue(message(score).contains("ck_p012_score_range"));
        SQLException facet =
                assertSqlRejected("update hr.promotion_request set review_facet_count=0 where id='" + PROMOTION + "'");
        assertTrue(message(facet).contains("ck_p012_review_scores"));
        SQLException node = assertSqlRejected(
                "update hr.promotion_request set current_node_code='S99' where id='" + PROMOTION + "'");
        assertTrue(message(node).contains("ck_p012_current_node"));
    }

    @Test
    void p012MigrationAndRlsAreInstalled() throws Exception {
        assertEquals(1L, scalarLong("select count(*) from flyway_schema_history where success and version='123'"));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_policies
                where schemaname='hr' and tablename='promotion_request'
                  and policyname='p_tenant_promotion_request')
                """));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_indexes
                where schemaname='org' and tablename='employee_position'
                  and indexname='uq_employee_position_promotion_effect')
                """));
    }

    private static void seedFacts() throws SQLException {
        execute(
                """
                insert into org.organization(id,tenant_id,org_code,org_name,org_type,status)
                values('%s','%s','PHASE11-P012-CENTER','PHASE-11 P012 Center','CENTER','ACTIVE')
                """
                        .formatted(CENTER, TENANT));
        execute(
                """
                insert into org.position(id,tenant_id,position_code,position_name,org_id,status)
                values
                  ('%s','%s','P012-CURRENT','Current Position','%s','ACTIVE'),
                  ('%s','%s','P012-TARGET','Target Position','%s','ACTIVE')
                """
                        .formatted(CURRENT_POSITION, TENANT, CENTER, TARGET_POSITION, TENANT, CENTER));
        execute(
                """
                insert into org.employee(
                  id,tenant_id,employee_no,person_name,employment_status,hire_date,
                  primary_org_id,primary_position_id)
                values('%s','%s','P012-EMPLOYEE','P012 Employee','ACTIVE',
                  date '2026-01-01','%s','%s')
                """
                        .formatted(EMPLOYEE, TENANT, CENTER, CURRENT_POSITION));
        execute(
                """
                insert into performance.performance_cycle(
                  id,tenant_id,business_no,status,current_node_code,version_no,
                  subject,owner_center_id,owner_employee_id,content_version,
                  employee_event_type,fact_occurred_at,fact_summary,period_or_course_no,
                  score_1000,calibrated_score_1000)
                values('%s','%s','P011-P012-SOURCE','已关闭','END',11,
                  'P012 source performance','%s','%s','V1','P011_PERFORMANCE',
                  timestamptz '2026-07-01 00:00:00+00','closed score','2026-Q3',850,880)
                """
                        .formatted(CYCLE, TENANT, CENTER, EMPLOYEE));
        execute(
                """
                insert into performance.performance_score_entry(
                  id,tenant_id,cycle_id,score_type,score_1000,source_fact_key,
                  evidence_summary,submitted_by)
                values(gen_random_uuid(),'%s','%s','EMPLOYEE',880,'%s:EMPLOYEE',
                  'promotion fact','%s')
                """
                        .formatted(TENANT, CYCLE, CYCLE, EMPLOYEE));
        execute(
                """
                insert into hr.promotion_request(
                  id,tenant_id,business_no,status,current_node_code,version_no,
                  business_date,subject,reason,priority,risk_level,owner_center_id,
                  owner_employee_id,fact_occurred_at,fact_summary,
                  employment_type,period_or_course_no,person_name,person_no,
                  planned_effective_date,target_job_id,
                  source_performance_cycle_id,current_position_id,target_position_id,
                  fsm_state,timebox_state,qa_state,review_facet_count,
                  weighted_review_score,promotion_threshold_score,content_version,
                  nomination_summary)
                values('%s','%s','P012-DB-TEST','正式生效','S10',9,date '2026-08-01',
                  'P012 appointment test','promotion','NORMAL','NORMAL','%s','%s',
                  timestamptz '2026-08-01 00:00:00+00','promotion fact',
                  'PROMOTION','2026-Q3','P012 Employee','P012-EMPLOYEE',
                  date '2026-09-01','P012-TARGET','%s','%s','%s',
                  'CLOSED','FINISHED','QA_PASS',1,880,800,'P012-CONTENT-V1','nomination')
                """
                        .formatted(PROMOTION, TENANT, CENTER, EMPLOYEE, CYCLE, CURRENT_POSITION, TARGET_POSITION));
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
                        "sjg_tenant_code", "PHASE11_P012_GATE",
                        "sjg_tenant_name", "PHASE-11 P012 Gate Tenant"))
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
