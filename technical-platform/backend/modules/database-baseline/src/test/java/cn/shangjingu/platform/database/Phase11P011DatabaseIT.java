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

class Phase11P011DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001111");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001111");
    private static final UUID EMPLOYEE = UUID.fromString("02000000-0000-0000-0000-000000001111");
    private static final UUID CYCLE = UUID.fromString("10000000-0000-0000-0000-000000001111");
    private static final UUID SCORE = UUID.fromString("20000000-0000-0000-0000-000000001111");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase11-p011-" + UUID.randomUUID());
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
    void p011PublishedGraphMatchesFrozenContract() throws Exception {
        assertEquals(
                "S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,S11,END",
                scalarString(
                        """
                        select string_agg(n.node_code,',' order by n.sort_no)
                        from workflow.wf_node n
                        join workflow.wf_version v
                          on v.tenant_id=n.tenant_id and v.id=n.version_id
                        join workflow.wf_definition d
                          on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where d.tenant_id='%s' and d.process_code='P011'
                          and v.status='PUBLISHED'
                          and v.checksum='phase11-p011-c0-v1'
                          and not n.is_deleted and not v.is_deleted and not d.is_deleted
                        """
                                .formatted(TENANT)));
        assertEquals(
                11L,
                scalarLong(
                        """
                select count(*)
                from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P011'
                  and v.status='PUBLISHED' and v.checksum='phase11-p011-c0-v1'
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """
                                .formatted(TENANT)));
    }

    @Test
    void scoreFactsAreIndependentAndImmutable() throws Exception {
        assertEquals(
                1L, scalarLong("select count(*) from performance.performance_score_entry where id='" + SCORE + "'"));
        SQLException update = assertSqlRejected(
                "update performance.performance_score_entry set score_1000=999 where id='" + SCORE + "'");
        assertTrue(message(update).contains("P011 performance score facts are append-only"));
        SQLException delete =
                assertSqlRejected("delete from performance.performance_score_entry where id='" + SCORE + "'");
        assertTrue(message(delete).contains("P011 performance score facts are append-only"));
        assertEquals(
                800L,
                scalarLong("select employee_score_1000 from performance.performance_cycle where id='" + CYCLE + "'"));
    }

    @Test
    void duplicateScoreTypeAndOutOfRangeScoresFailClosed() {
        SQLException duplicate = assertSqlRejected(
                """
                insert into performance.performance_score_entry(
                  id,tenant_id,cycle_id,score_type,score_1000,source_fact_key,
                  evidence_summary,submitted_by)
                values(gen_random_uuid(),'%s','%s','EMPLOYEE',700,'duplicate','duplicate','%s')
                """
                        .formatted(TENANT, CYCLE, EMPLOYEE));
        assertTrue(message(duplicate).contains("uq_performance_score_entry_type"));
        SQLException range = assertSqlRejected(
                "update performance.performance_cycle set supervisor_score_1000=1001 where id='" + CYCLE + "'");
        assertTrue(message(range).contains("ck_p011_score_sources_1000"));
    }

    @Test
    void p011MigrationAndRlsAreInstalled() throws Exception {
        assertEquals(1L, scalarLong("select count(*) from flyway_schema_history where success and version='122'"));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_trigger
                where tgname='trg_performance_score_immutable' and not tgisinternal)
                """));
        assertTrue(
                scalarBoolean(
                        """
                select exists(select 1 from pg_policies
                where schemaname='performance'
                  and tablename='performance_score_entry'
                  and policyname='p_tenant_performance_score_entry')
                """));
    }

    private static void seedFacts() throws SQLException {
        execute(
                """
                insert into org.organization(id,tenant_id,org_code,org_name,org_type,status)
                values('%s','%s','PHASE11-P011-CENTER','PHASE-11 P011 Center','CENTER','ACTIVE')
                """
                        .formatted(CENTER, TENANT));
        execute(
                """
                insert into org.employee(
                  id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id)
                values('%s','%s','PHASE11-P011-EMPLOYEE','P011 Employee','ACTIVE',date '2026-01-01','%s')
                """
                        .formatted(EMPLOYEE, TENANT, CENTER));
        execute(
                """
                insert into performance.performance_cycle(
                  id,tenant_id,business_no,status,current_node_code,version_no,
                  subject,owner_center_id,owner_employee_id,content_version,
                  employee_event_type,fact_occurred_at,fact_summary,period_or_course_no,
                  employee_score_1000)
                values('%s','%s','P011-DB-TEST','员工自评/主管评价','S05',1,
                  'P011 score source test','%s','%s','V1','P011_PERFORMANCE',
                  timestamptz '2026-07-01 00:00:00+00','independent scores','2026-Q3',800)
                """
                        .formatted(CYCLE, TENANT, CENTER, EMPLOYEE));
        execute(
                """
                insert into performance.performance_score_entry(
                  id,tenant_id,cycle_id,score_type,score_1000,source_fact_key,
                  evidence_summary,submitted_by)
                values('%s','%s','%s','EMPLOYEE',800,'%s:EMPLOYEE','employee evidence','%s')
                """
                        .formatted(SCORE, TENANT, CYCLE, CYCLE, EMPLOYEE));
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
                        "sjg_tenant_code", "PHASE11_P011_GATE",
                        "sjg_tenant_name", "PHASE-11 P011 Gate Tenant"))
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
