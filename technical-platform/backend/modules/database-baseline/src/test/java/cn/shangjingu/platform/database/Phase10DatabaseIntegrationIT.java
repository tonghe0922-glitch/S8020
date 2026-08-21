package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.workflow.JdbcLeaveRepository;
import cn.shangjingu.platform.workflow.JdbcShiftChangeRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PHASE-10 real PostgreSQL gate.
 *
 * <p>Installs the production baseline plus overlays and proves protections that compilation and
 * mock-only tests cannot represent: append-only ledgers, guarded actual-time facts, P010 evidence
 * and qualification constraints, cross-process attendance conflicts, successor workflow history,
 * and the executable latest P006-P010 workflow graphs.</p>
 */
class Phase10DatabaseIntegrationIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000001010");
    private static final UUID CENTER = UUID.fromString("01000000-0000-0000-0000-000000001010");
    private static final UUID EMPLOYEE = UUID.fromString("02000000-0000-0000-0000-000000001010");
    private static final UUID LEAVE = UUID.fromString("10000000-0000-0000-0000-000000001010");
    private static final UUID LEDGER = UUID.fromString("20000000-0000-0000-0000-000000001010");
    private static final UUID MUTABLE_LEAVE_ITEM = UUID.fromString("21000000-0000-0000-0000-000000001010");
    private static final UUID OVERTIME = UUID.fromString("22000000-0000-0000-0000-000000001010");
    private static final UUID MUTABLE_OVERTIME_ITEM = UUID.fromString("23000000-0000-0000-0000-000000001010");
    private static final UUID ASSIGNMENT = UUID.fromString("30000000-0000-0000-0000-000000001010");
    private static final UUID EVIDENCE = UUID.fromString("40000000-0000-0000-0000-000000001010");

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;

    @BeforeAll
    static void installProductionSchema() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase10-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
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
    void p008QuotaLedgerRejectsUpdateAndDelete() throws Exception {
        assertEquals("1.00000000", scalarString("select item_value_number::text from attendance.leave_request_item where id='" + LEDGER + "'"));
        SQLException update = assertSqlRejected("update attendance.leave_request_item set item_value_number=2 where id='" + LEDGER + "'");
        assertTrue(message(update).contains("P008 quota ledger is append-only"));
        SQLException delete = assertSqlRejected("delete from attendance.leave_request_item where id='" + LEDGER + "'");
        assertTrue(message(delete).contains("P008 quota ledger is append-only"));
        assertEquals(1L, scalarLong("select count(*) from attendance.leave_request_item where id='" + LEDGER + "'"));
    }

    @Test
    void ledgerRowsCannotBeCreatedByMutatingOrdinaryItems() {
        SQLException leaveConversion = assertSqlRejected(
                "update attendance.leave_request_item set field_code='QUOTA_LEDGER',item_key='ADJUST',item_value_number=1 where id='"
                        + MUTABLE_LEAVE_ITEM + "'");
        assertTrue(message(leaveConversion).contains("P008 quota ledger entries must be inserted"));
        SQLException overtimeConversion = assertSqlRejected(
                "update attendance.overtime_request_item set field_code='TIME_OFF_LEDGER',item_key='GRANT',item_value_number=1 where id='"
                        + MUTABLE_OVERTIME_ITEM + "'");
        assertTrue(message(overtimeConversion).contains("P009 time-off ledger entries must be inserted"));
    }

    @Test
    void p008ReturnFactRejectsTimeBeforeActualLeaveStart() throws Exception {
        JdbcLeaveRepository repository = new JdbcLeaveRepository(jdbc());
        assertEquals(0, repository.markReturned(TENANT, LEAVE, Instant.parse("2026-08-12T09:59:59Z"), EMPLOYEE));
        assertEquals(1, repository.markReturned(TENANT, LEAVE, Instant.parse("2026-08-12T12:00:00Z"), EMPLOYEE));
        assertEquals(
                "2026-08-12T12:00:00Z",
                scalarString("select to_char(actual_end_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from attendance.leave_request where id='"
                        + LEAVE + "'"));
    }

    @Test
    void p007DetectsActiveLeaveAndOvertimeAcrossCanonicalTables() {
        JdbcShiftChangeRepository repository = new JdbcShiftChangeRepository(jdbc());
        assertTrue(repository.hasAttendanceConflict(
                TENANT, EMPLOYEE, Instant.parse("2026-08-12T11:00:00Z"), Instant.parse("2026-08-12T12:00:00Z")));
        assertTrue(repository.hasAttendanceConflict(
                TENANT, EMPLOYEE, Instant.parse("2026-08-13T10:30:00Z"), Instant.parse("2026-08-13T11:30:00Z")));
        assertFalse(repository.hasAttendanceConflict(
                TENANT, EMPLOYEE, Instant.parse("2026-08-14T10:30:00Z"), Instant.parse("2026-08-14T11:30:00Z")));
    }

    @Test
    void p010EvidenceRejectsMutationAndQualificationConstraintsFailClosed() throws Exception {
        SQLException update = assertSqlRejected(
                "update learning.learning_assignment_evidence set evidence_text='tampered' where id='" + EVIDENCE + "'");
        assertTrue(message(update).contains("learning assignment evidence is append-only"));
        SQLException delete = assertSqlRejected(
                "delete from learning.learning_assignment_evidence where id='" + EVIDENCE + "'");
        assertTrue(message(delete).contains("learning assignment evidence is append-only"));
        assertEquals(1L, scalarLong("select count(*) from learning.learning_assignment_evidence where id='" + EVIDENCE + "'"));
        SQLException score = assertSqlRejected(
                "update learning.learning_assignment set score_1000=1001 where id='" + ASSIGNMENT + "'");
        assertTrue(message(score).contains("ck_p010_score_1000"));
        SQLException dates = assertSqlRejected(
                "update learning.learning_assignment set qualification_effective_date=date '2026-08-13',qualification_expire_date=date '2026-08-12' where id='"
                        + ASSIGNMENT + "'");
        assertTrue(message(dates).contains("ck_p010_qualification_dates"));
    }

    @Test
    void latestPublishedP006ToP010GraphsMatchApprovedSourceNodes() throws Exception {
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,S11,END", nodes("P006"));
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,END", nodes("P007"));
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,END", nodes("P008"));
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,END", nodes("P009"));
        assertEquals("S01,S02,S03,S04,S05,S06,S07,S08,S09,S10,END", nodes("P010"));

        assertEquals(13L, transitionCount("P008"));
        assertEquals(11L, transitionCount("P010"));
        assertEquals(1L, transitionCount("P008", "S05", "RELEASE_QUOTA", "END"));
        assertEquals(1L, transitionCount("P010", "S08", "LINK_PERMISSIONS", "S09"));

        assertTrue(publishedVersionCount("P007") >= 2L);
        assertTrue(publishedVersionCount("P008") >= 2L);
        assertTrue(publishedVersionCount("P009") >= 2L);
    }

    @Test
    void phase10MigrationsAreInstalledAndValidated() throws Exception {
        assertEquals(5L, scalarLong(
                "select count(*) from flyway_schema_history where success and version in ('117','118','119','120','121')"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_trigger where tgname='trg_p008_quota_ledger_immutable' and not tgisinternal)"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_trigger where tgname='trg_p009_timeoff_ledger_immutable' and not tgisinternal)"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_trigger where tgname='trg_assignment_evidence_immutable' and not tgisinternal)"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_constraint where conname='ck_p008_quota_ledger_entry')"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_constraint where conname='ck_p009_timeoff_ledger_entry')"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_constraint where conname='ck_p009_timeoff_ledger_type')"));
        assertTrue(scalarBoolean("select exists(select 1 from pg_constraint where conname='ck_p008_return_after_leave_start')"));
    }

    private static void seedFacts() throws SQLException {
        execute("""
                insert into org.organization(id,tenant_id,org_code,org_name,org_type,status)
                values('%s','%s','PHASE10-DB-CENTER','PHASE-10 DB Gate Center','CENTER','ACTIVE')
                """.formatted(CENTER, TENANT));
        execute("""
                insert into org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id)
                values('%s','%s','PHASE10-DB-EMPLOYEE','PHASE-10 DB Gate Employee','ACTIVE',date '2026-08-12','%s')
                """.formatted(EMPLOYEE, TENANT, CENTER));
        execute("""
                insert into attendance.leave_request(
                  id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,
                  attendance_type,change_action,change_reason,duration_hours,start_at,end_at,quota_account_id,quota_amount,
                  attendance_marked_at,leave_started_at,actual_start_at)
                values('%s','%s','P008-DB-TEST','实际休假',0,'append-only and temporal test','%s','%s',
                  'ANNUAL_LEAVE','CREATE','test',8,timestamptz '2026-08-12 10:00:00+00',
                  timestamptz '2026-08-12 18:00:00+00','ANNUAL-2026',1,
                  timestamptz '2026-08-12 09:00:00+00',timestamptz '2026-08-12 10:00:00+00',
                  timestamptz '2026-08-12 10:00:00+00')
                """.formatted(LEAVE, TENANT, CENTER, EMPLOYEE));
        execute("""
                insert into attendance.leave_request_item(
                  id,tenant_id,master_id,field_code,item_seq,item_key,item_name,item_value_number,item_value_text)
                values('%s','%s','%s','QUOTA_LEDGER',1,'RESERVE','额度预占',1,'created by PHASE-10 DB gate')
                """.formatted(LEDGER, TENANT, LEAVE));
        execute("""
                insert into attendance.leave_request_item(
                  id,tenant_id,master_id,field_code,item_seq,item_key,item_name,item_value_text)
                values('%s','%s','%s','HANDOVER',2,'NOTE','普通明细','mutable source row')
                """.formatted(MUTABLE_LEAVE_ITEM, TENANT, LEAVE));
        execute("""
                insert into attendance.overtime_request(
                  id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,
                  attendance_type,duration_hours,start_at,end_at,emergency_fact)
                values('%s','%s','P009-DB-TEST','主管审批',0,'cross-process conflict test','%s','%s',
                  'OVERTIME',2,timestamptz '2026-08-13 10:00:00+00',timestamptz '2026-08-13 12:00:00+00',false)
                """.formatted(OVERTIME, TENANT, CENTER, EMPLOYEE));
        execute("""
                insert into attendance.overtime_request_item(
                  id,tenant_id,master_id,field_code,item_seq,item_key,item_name,item_value_text)
                values('%s','%s','%s','ATTENDANCE_EVIDENCE',1,'NOTE','普通明细','mutable source row')
                """.formatted(MUTABLE_OVERTIME_ITEM, TENANT, OVERTIME));
        execute("""
                insert into learning.learning_assignment(
                  id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,
                  completion_rate,content_version,course_team_name,course_version_id,period_or_course_no,
                  phase_node_code,score_1000,qualification_effective_date,qualification_expire_date)
                values('%s','%s','P010-DB-TEST','员工学习',0,'qualification test','%s','%s',
                  100,'v1','安全培训组','COURSE-001','2026-A','S03',900,date '2026-08-12',date '2027-08-12')
                """.formatted(ASSIGNMENT, TENANT, CENTER, EMPLOYEE));
        execute("""
                insert into learning.learning_assignment_evidence(
                  id,tenant_id,assignment_id,evidence_type,score_1000,completion_rate,evidence_text,evidence_json)
                values('%s','%s','%s','EXAM_ATTEMPT',900,100,'immutable evidence','{}'::jsonb)
                """.formatted(EVIDENCE, TENANT, ASSIGNMENT));
    }

    private static String nodes(String process) throws SQLException {
        return scalarString("""
                select string_agg(n.node_code,',' order by n.sort_no)
                from workflow.wf_node n
                join workflow.wf_version v on v.tenant_id=n.tenant_id and v.id=n.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='%s'
                  and v.id=(
                    select v2.id from workflow.wf_version v2
                    where v2.tenant_id=v.tenant_id and v2.definition_id=v.definition_id
                      and v2.status='PUBLISHED' and not v2.is_deleted
                    order by v2.version_no desc,v2.created_at desc,v2.id desc limit 1)
                  and not n.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT, process));
    }

    private static long transitionCount(String process) throws SQLException {
        return scalarLong("""
                select count(*)
                from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='%s'
                  and v.id=(
                    select v2.id from workflow.wf_version v2
                    where v2.tenant_id=v.tenant_id and v2.definition_id=v.definition_id
                      and v2.status='PUBLISHED' and not v2.is_deleted
                    order by v2.version_no desc,v2.created_at desc,v2.id desc limit 1)
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT, process));
    }

    private static long transitionCount(String process, String from, String action, String to) throws SQLException {
        return scalarLong("""
                select count(*)
                from workflow.wf_transition t
                join workflow.wf_version v on v.tenant_id=t.tenant_id and v.id=t.version_id
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='%s'
                  and v.id=(
                    select v2.id from workflow.wf_version v2
                    where v2.tenant_id=v.tenant_id and v2.definition_id=v.definition_id
                      and v2.status='PUBLISHED' and not v2.is_deleted
                    order by v2.version_no desc,v2.created_at desc,v2.id desc limit 1)
                  and t.from_node_code='%s' and t.action_code='%s' and t.to_node_code='%s'
                  and not t.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT, process, from, action, to));
    }

    private static long publishedVersionCount(String process) throws SQLException {
        return scalarLong("""
                select count(*)
                from workflow.wf_version v
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s' and d.process_code='%s'
                  and v.status='PUBLISHED' and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT, process));
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
            if (current.getMessage() != null) {
                result.append(current.getMessage()).append(' ');
            }
        }
        Throwable cause = error.getCause();
        while (cause != null) {
            if (cause.getMessage() != null) {
                result.append(cause.getMessage()).append(' ');
            }
            cause = cause.getCause();
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
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement()) {
            assertFalse(statement.execute(sql));
        }
    }

    private static JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl("sjg_oms"));
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE10_GATE",
                        "sjg_tenant_name", "PHASE-10 Gate Tenant"))
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
