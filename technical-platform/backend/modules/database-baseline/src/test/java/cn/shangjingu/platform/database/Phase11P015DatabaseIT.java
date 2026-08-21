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

class Phase11P015DatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001115");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001115");
    private static final UUID POSITION = UUID.fromString("02000000-0000-0000-0000-000000001115");
    private static final UUID EMPLOYEE = UUID.fromString("03000000-0000-0000-0000-000000001115");
    private static final UUID ORIGINAL = UUID.fromString("10000000-0000-0000-0000-000000001115");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres").withUsername("postgres")
                .withPassword("phase11-p015-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("create database sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        seedOrg();
        seedOriginalPosting();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void p015PublishedGraphMatchesFrozenContract() throws Exception {
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,END", scalarString("""
                select string_agg(n.node_code,',' order by n.sort_no)
                from workflow.wf_node n
                join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P015' and v.status='PUBLISHED'
                  and v.checksum='phase11-p015-c0-v1' and not n.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT)));
        assertEquals(10L, scalarLong("""
                select count(*) from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='P015' and v.status='PUBLISHED'
                  and v.checksum='phase11-p015-c0-v1' and not t.is_deleted
                """.formatted(TENANT)));
        assertEquals(1L, scalarLong("""
                select count(*) from workflow.wf_form_definition
                 where tenant_id='%s' and process_code='P015' and form_code='CTR-P015-F01'
                   and node_code='S01' and enabled and not is_deleted
                """.formatted(TENANT)));
    }

    @Test
    void migrationDoesNotInventPublishedPointValues() throws Exception {
        assertEquals(0L, scalarLong("""
                select count(*) from reward.point_rule_version
                 where tenant_id='%s' and status='PUBLISHED' and not is_deleted
                """.formatted(TENANT)));
    }

    @Test
    void postgresRejectsUpdateAndDeleteOfP015Ledger() {
        SQLException update = assertSqlRejected("""
                update reward.point_transaction set points_delta=999 where id='%s'
                """.formatted(ORIGINAL));
        assertTrue(message(update).contains("append-only"));
        SQLException delete = assertSqlRejected("""
                delete from reward.point_transaction where id='%s'
                """.formatted(ORIGINAL));
        assertTrue(message(delete).contains("append-only"));
    }

    @Test
    void reversalIsNewOppositeLedgerAndOriginalNeverChanges() throws Exception {
        execute("""
                insert into reward.point_transaction(
                  id,tenant_id,business_no,status,version_no,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,change_action,change_reason,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,points_delta,source_type,point_type,rule_code,rule_version,calculation_snapshot,
                  risk_class,root_transaction_id,reversal_of_id,correction_evidence)
                values(gen_random_uuid(),'%s','P015-REV-1','POSTED',0,'TEST',date '2026-08-16','reversal','correction',
                  'NORMAL','CONTROLLED','%s','%s','REVERSAL','approved correction','P015_POINTS',now(),
                  'reverse original','EMPLOYEE',-25,'INTERNAL','GROWTH','RULE-TEST','V1','{}'::jsonb,
                  'CONTROLLED','%s','%s','{"reference":"CORR-1"}'::jsonb)
                """.formatted(TENANT, CENTER, EMPLOYEE, ORIGINAL, ORIGINAL));
        assertEquals(25L, scalarLong("select points_delta from reward.point_transaction where id='%s'".formatted(ORIGINAL)));
        assertEquals(0L, scalarLong("""
                select sum(points_delta) from reward.point_transaction
                 where tenant_id='%s' and owner_employee_id='%s' and point_type='GROWTH'
                   and employee_event_type='P015_POINTS' and not is_deleted
                """.formatted(TENANT, EMPLOYEE)));
        assertEquals(1L, scalarLong("""
                select count(*) from reward.point_transaction
                 where tenant_id='%s' and reversal_of_id='%s' and change_action='REVERSAL'
                """.formatted(TENANT, ORIGINAL)));
    }

    @Test
    void sourceGuardAndOriginalPostingAreUniquePerTenant() throws Exception {
        execute("""
                insert into reward.point_source_guard(id,tenant_id,source_fact_key,point_case_id)
                values(gen_random_uuid(),'%s','GUARD-1',gen_random_uuid())
                """.formatted(TENANT));
        SQLException duplicateGuard = assertSqlRejected("""
                insert into reward.point_source_guard(id,tenant_id,source_fact_key,point_case_id)
                values(gen_random_uuid(),'%s','GUARD-1',gen_random_uuid())
                """.formatted(TENANT));
        assertTrue(message(duplicateGuard).contains("uq_p015_source_guard"));

        SQLException duplicatePost = assertSqlRejected("""
                insert into reward.point_transaction(
                  id,tenant_id,business_no,status,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,change_action,change_reason,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,points_delta,source_fact_key,source_type,point_type,rule_code,rule_version,
                  calculation_snapshot,risk_class)
                values(gen_random_uuid(),'%s','P015-DUP-POST','POSTED','TEST',date '2026-08-16','dup','dup','NORMAL','NORMAL',
                  '%s','%s','POST','dup','P015_POINTS',now(),'dup','EMPLOYEE',25,'P015-SOURCE-1','INTERNAL','GROWTH',
                  'RULE-TEST','V1','{}'::jsonb,'NORMAL')
                """.formatted(TENANT, CENTER, EMPLOYEE));
        assertTrue(message(duplicatePost).contains("uq_p015_source_post"));
    }

    @Test
    void p015MigrationAndSupportingRlsAreInstalled() throws Exception {
        assertEquals(1L, scalarLong("select count(*) from flyway_schema_history where success and version='126'"));
        for (String table : List.of("point_rule_version", "point_source_guard", "point_balance_snapshot")) {
            assertTrue(scalarBoolean("select relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                    + "where n.nspname='reward' and c.relname='" + table + "'"));
        }
        assertTrue(scalarBoolean("""
                select exists(select 1 from pg_trigger where tgname='trg_p015_point_transaction_immutable' and not tgisinternal)
                """));
    }

    private static void seedOrg() throws SQLException {
        execute("insert into org.organization(id,tenant_id,org_code,org_name,org_type,status) values('%s','%s','P015-C','P015 Center','CENTER','ACTIVE')"
                .formatted(CENTER, TENANT));
        execute("insert into org.position(id,tenant_id,position_code,position_name,org_id,status) values('%s','%s','P015-P','P015 Position','%s','ACTIVE')"
                .formatted(POSITION, TENANT, CENTER));
        execute("""
                insert into org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id)
                values('%s','%s','P015-E','P015 Employee','ACTIVE',date '2026-01-01','%s','%s')
                """.formatted(EMPLOYEE, TENANT, CENTER, POSITION));
    }

    private static void seedOriginalPosting() throws SQLException {
        execute("""
                insert into reward.point_transaction(
                  id,tenant_id,business_no,status,version_no,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,change_action,change_reason,employee_event_type,fact_occurred_at,
                  fact_summary,impact_level,points_delta,source_fact_key,source_type,point_type,rule_code,rule_version,
                  calculation_snapshot,risk_class)
                values('%s','%s','P015-POST-1','POSTED',0,'TEST',date '2026-08-16','source event','verified','NORMAL','NORMAL',
                  '%s','%s','POST','verified','P015_POINTS',timestamptz '2026-08-16 00:00:00+00','verified event','EMPLOYEE',25,
                  'P015-SOURCE-1','INTERNAL','GROWTH','RULE-TEST','V1','{"points":25}'::jsonb,'NORMAL')
                """.formatted(ORIGINAL, TENANT, CENTER, EMPLOYEE));
    }

    private static SQLException assertSqlRejected(String sql) {
        return assertThrows(SQLException.class, () -> {
            try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }
    private static String message(SQLException error) {
        StringBuilder result = new StringBuilder();
        for (SQLException current = error; current != null; current = current.getNextException()) {
            if (current.getMessage() != null) result.append(current.getMessage()).append(' ');
        }
        return result.toString();
    }
    private static String scalarString(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getString(1);
        }
    }
    private static long scalarLong(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }
    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms"); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getBoolean(1);
        }
    }
    private static void execute(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms"); Statement s = c.createStatement()) { assertFalse(s.execute(sql)); }
    }
    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        Flyway flyway = Flyway.configure().dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of("sjg_tenant_id", TENANT.toString(), "sjg_tenant_code", "PHASE11_P015_GATE",
                        "sjg_tenant_name", "PHASE-11 P015 Gate Tenant"))
                .cleanDisabled(true).load();
        assertTrue(flyway.migrate().success); flyway.validate();
    }
    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }
    private static String jdbcUrl(String database) {
        String url = postgres.getJdbcUrl(); int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String base = query >= 0 ? url.substring(0, query) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }
    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md")) && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
