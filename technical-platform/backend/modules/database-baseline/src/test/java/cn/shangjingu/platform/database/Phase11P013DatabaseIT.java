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

class Phase11P013DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001113");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001113");
    private static final UUID EMPLOYEE = UUID.fromString("02000000-0000-0000-0000-000000001113");
    private static final UUID POSITION = UUID.fromString("03000000-0000-0000-0000-000000001113");
    private static final UUID REWARD = UUID.fromString("10000000-0000-0000-0000-000000001113");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase11-p013-" + UUID.randomUUID());
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
    void p013PublishedGraphMatchesFrozenContract() throws Exception {
        assertEquals(
                "S01,S02,S03,S04,S05,S06,S07,S08,S09,END",
                scalarString("""
                        select string_agg(n.node_code,',' order by n.sort_no)
                        from workflow.wf_node n
                        join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                        join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where d.tenant_id='%s' and d.process_code='P013'
                          and v.status='PUBLISHED' and v.checksum='phase11-p013-c0-v1'
                          and not n.is_deleted and not v.is_deleted and not d.is_deleted
                        """.formatted(TENANT)));
        assertEquals(9L, scalarLong("""
                select count(*) from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P013'
                  and v.status='PUBLISHED' and v.checksum='phase11-p013-c0-v1'
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT)));
    }

    @Test
    void oneSourceFactCreatesAtMostOneReward() {
        SQLException duplicate = assertSqlRejected("""
                insert into reward.reward_case(
                  id,tenant_id,business_no,status,current_node_code,subject,reason,
                  owner_center_id,owner_employee_id,benefit_amount,employee_event_type,
                  fact_occurred_at,fact_summary,impact_level,source_fact_key)
                values(gen_random_uuid(),'%s','P013-DUP','贡献事实登记','S01','duplicate','duplicate',
                  '%s','%s',0,'P013_REWARD',now(),'duplicate','CENTER','P013-SOURCE-1')
                """.formatted(TENANT, CENTER, EMPLOYEE));
        assertTrue(message(duplicate).contains("uq_p013_source_fact"));
    }

    @Test
    void rewardPointEffectIsExactlyOnce() throws Exception {
        execute(pointEffectInsert("P013-EFFECT-1"));
        SQLException duplicate = assertSqlRejected(pointEffectInsert("P013-EFFECT-2"));
        assertTrue(message(duplicate).contains("uq_p013_point_effect"));
        assertEquals(1L, scalarLong("""
                select count(*) from reward.point_transaction
                where tenant_id='%s' and source_reward_case_id='%s' and not is_deleted
                """.formatted(TENANT, REWARD)));
    }

    @Test
    void p013ConstraintsAndRlsFailClosed() throws Exception {
        SQLException node = assertSqlRejected(
                "update reward.reward_case set current_node_code='S99' where id='" + REWARD + "'");
        assertTrue(message(node).contains("ck_p013_current_node"));
        assertEquals(1L, scalarLong(
                "select count(*) from flyway_schema_history where success and version='124'"));
        assertTrue(scalarBoolean("""
                select exists(select 1 from pg_policies
                where schemaname='reward' and tablename='reward_case'
                  and policyname='p_tenant_reward_case')
                """));
        assertTrue(scalarBoolean("""
                select exists(select 1 from pg_indexes
                where schemaname='reward' and tablename='point_transaction'
                  and indexname='uq_p013_point_effect')
                """));
    }

    private static String pointEffectInsert(String businessNo) {
        return """
                insert into reward.point_transaction(
                  id,tenant_id,business_no,status,current_node_code,version_no,
                  source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,result_summary,closed_at,
                  actual_amount,benefit_amount,change_action,change_reason,
                  cost_center_id,currency,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,points_delta,source_fact_key,source_reward_case_id)
                values(gen_random_uuid(),'%s','%s','已入账','END',0,
                  'PHASE11_EFFECT',date '2026-08-16','reward effect','reward','NORMAL','NORMAL',
                  '%s','%s','effect',now(),0,0,'REWARD_POST','reward',
                  'NON_FINANCIAL','POINT','P013_REWARD_EFFECT',now(),
                  'reward fact','CENTER',10,'%s','%s')
                """.formatted(TENANT, businessNo, CENTER, EMPLOYEE, businessNo, REWARD);
    }

    private static void seedFacts() throws SQLException {
        execute("""
                insert into org.organization(id,tenant_id,org_code,org_name,org_type,status)
                values('%s','%s','PHASE11-P013-CENTER','PHASE-11 P013 Center','CENTER','ACTIVE')
                """.formatted(CENTER, TENANT));
        execute("""
                insert into org.position(id,tenant_id,position_code,position_name,org_id,status)
                values('%s','%s','P013-POS','P013 Position','%s','ACTIVE')
                """.formatted(POSITION, TENANT, CENTER));
        execute("""
                insert into org.employee(
                  id,tenant_id,employee_no,person_name,employment_status,hire_date,
                  primary_org_id,primary_position_id)
                values('%s','%s','P013-EMPLOYEE','P013 Employee','ACTIVE',
                  date '2026-01-01','%s','%s')
                """.formatted(EMPLOYEE, TENANT, CENTER, POSITION));
        execute("""
                insert into reward.reward_case(
                  id,tenant_id,business_no,status,current_node_code,version_no,
                  business_date,subject,reason,priority,risk_level,owner_center_id,
                  owner_employee_id,benefit_amount,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,points_delta,source_fact_key,content_version,period_no)
                values('%s','%s','P013-DB-TEST','奖励执行','S06',5,date '2026-08-16',
                  'P013 reward test','reward','NORMAL','NORMAL','%s','%s',0,
                  'P013_REWARD',timestamptz '2026-08-16 00:00:00+00',
                  'reward fact','CENTER',10,'P013-SOURCE-1','P013-CONTENT-V1','2026-Q3')
                """.formatted(REWARD, TENANT, CENTER, EMPLOYEE));
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
        locations.add("filesystem:" + repoRoot
                .resolve("technical-platform/database/flyway")
                .resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:" + repoRoot
                    .resolve("technical-platform/database/flyway-overlays")
                    .resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE11_P013_GATE",
                        "sjg_tenant_name", "PHASE-11 P013 Gate Tenant"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
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
