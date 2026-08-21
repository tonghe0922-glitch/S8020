package cn.shangjingu.platform.database.hardening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.GuardedLeaveRepository;
import cn.shangjingu.platform.workflow.GuardedOvertimeRepository;
import cn.shangjingu.platform.workflow.GuardedShiftChangeRepository;
import cn.shangjingu.platform.workflow.JdbcLeaveRepository;
import cn.shangjingu.platform.workflow.JdbcOvertimeRepository;
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

/** Real PostgreSQL evidence for PHASE-10 ledger, temporal and cross-process hardening. */
class Phase10DatabaseIntegrationIT {
    private static final String IMAGE="postgres:16.14-alpine3.24";
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000001120");
    private static final UUID CENTER=UUID.fromString("01000000-0000-0000-0000-000000001120");
    private static final UUID EMPLOYEE=UUID.fromString("02000000-0000-0000-0000-000000001120");
    private static final UUID SHIFT_EMPLOYEE=UUID.fromString("03000000-0000-0000-0000-000000001120");
    private static final UUID NO_FACT_EMPLOYEE=UUID.fromString("04000000-0000-0000-0000-000000001120");
    private static final UUID LEAVE=UUID.fromString("10000000-0000-0000-0000-000000001120");
    private static final UUID OVERTIME=UUID.fromString("20000000-0000-0000-0000-000000001120");
    private static final UUID SHIFT=UUID.fromString("30000000-0000-0000-0000-000000001120");
    private static final UUID LEAVE_ITEM=UUID.fromString("40000000-0000-0000-0000-000000001120");
    private static final UUID OVERTIME_ITEM=UUID.fromString("50000000-0000-0000-0000-000000001120");
    private static final Instant START=Instant.parse("2026-08-13T01:00:00Z");
    private static final Instant END=Instant.parse("2026-08-13T09:00:00Z");
    private static PostgreSQLContainer<?> postgres;
    private static Path root;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void install() throws Exception {
        root=findRoot();
        postgres=new PostgreSQLContainer<>(IMAGE).withDatabaseName("postgres")
                .withUsername("postgres").withPassword("phase10-"+UUID.randomUUID());
        postgres.start();
        migrate("postgres","cluster",null);
        try(Connection c=admin("postgres");Statement s=c.createStatement()){s.execute("create database sjg_oms");}
        migrate("sjg_oms","oms","oms");
        DriverManagerDataSource ds=new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");ds.setUrl(url("sjg_oms"));
        ds.setUsername(postgres.getUsername());ds.setPassword(postgres.getPassword());
        jdbc=new JdbcTemplate(ds);
        seed();
    }

    @AfterAll static void stop(){if(postgres!=null)postgres.stop();}

    @Test
    void ledgersRejectConversionAndMalformedFacts() throws Exception {
        SQLException p008=rejected("update attendance.leave_request_item set field_code='QUOTA_LEDGER',item_key='RESERVE',item_value_number=1,quantity=1 where id='"+LEAVE_ITEM+"'");
        assertTrue(message(p008).contains("must be inserted"));
        SQLException p009=rejected("update attendance.overtime_request_item set field_code='TIME_OFF_LEDGER',item_key='GRANT',item_value_number=1,quantity=1 where id='"+OVERTIME_ITEM+"'");
        assertTrue(message(p009).contains("must be inserted"));
        assertEquals("HANDOVER_ITEMS",string("select field_code from attendance.leave_request_item where id='"+LEAVE_ITEM+"'"));
        assertEquals("ATTENDANCE_FACT",string("select field_code from attendance.overtime_request_item where id='"+OVERTIME_ITEM+"'"));

        SQLException zeroAdjust=rejected("insert into attendance.leave_request_item(id,tenant_id,master_id,field_code,item_seq,item_key,item_value_number,quantity) values(gen_random_uuid(),'"+TENANT+"','"+LEAVE+"','QUOTA_LEDGER',9,'ADJUST',0,0)");
        assertTrue(message(zeroAdjust).contains("ck_p008_quota_ledger_entry"));
        SQLException zeroGrant=rejected("insert into attendance.overtime_request_item(id,tenant_id,master_id,field_code,item_seq,item_key,item_value_number,quantity) values(gen_random_uuid(),'"+TENANT+"','"+OVERTIME+"','TIME_OFF_LEDGER',9,'GRANT',0,0)");
        assertTrue(message(zeroGrant).contains("ck_p009_timeoff_ledger_entry"));
        SQLException wrongType=rejected("insert into attendance.overtime_request_item(id,tenant_id,master_id,field_code,item_seq,item_key,item_value_number,quantity) values(gen_random_uuid(),'"+TENANT+"','"+OVERTIME+"','TIME_OFF_LEDGER',10,'DEDUCT',1,1)");
        assertTrue(message(wrongType).contains("ck_p009_timeoff_ledger_type"));
    }

    @Test
    void returnChronologyFailsClosedInServiceAndDatabase() throws Exception {
        GuardedLeaveRepository repository=new GuardedLeaveRepository(new JdbcLeaveRepository(jdbc));
        ProcessRejectedException guard=assertThrows(ProcessRejectedException.class,
                ()->repository.markReturned(TENANT,LEAVE,START.minusSeconds(1),EMPLOYEE));
        assertTrue(guard.getMessage().contains("cannot precede"));
        assertEquals(1,repository.markReturned(TENANT,LEAVE,END,EMPLOYEE));
        SQLException constraint=rejected("update attendance.leave_request set returned_at='"+START.minusSeconds(1)+"' where id='"+LEAVE+"'");
        assertTrue(message(constraint).contains("ck_p008_return_after_leave_start"));
        SQLException actualWindow=rejected("update attendance.leave_request set actual_end_at='"+START.minusSeconds(1)+"' where id='"+LEAVE+"'");
        assertTrue(message(actualWindow).contains("ck_p008_actual_leave_window"));
    }

    @Test
    void crossProcessConflictChecksReadCanonicalFacts() {
        GuardedShiftChangeRepository shifts=new GuardedShiftChangeRepository(new JdbcShiftChangeRepository(jdbc),jdbc);
        assertTrue(shifts.hasOverlappingShift(TENANT,EMPLOYEE,START,END,UUID.randomUUID()));
        assertFalse(shifts.hasOverlappingShift(TENANT,NO_FACT_EMPLOYEE,START,END,UUID.randomUUID()));
        GuardedOvertimeRepository overtime=new GuardedOvertimeRepository(new JdbcOvertimeRepository(jdbc),jdbc);
        assertTrue(overtime.hasPlanningConflict(TENANT,SHIFT_EMPLOYEE,START,END));
        assertFalse(overtime.hasPlanningConflict(TENANT,NO_FACT_EMPLOYEE,START,END));
    }

    @Test
    void migration121PublishesExecutableEmployeeSelfServiceNodes() throws Exception {
        assertEquals(1L,number("select count(*) from flyway_schema_history where success and version='121'"));
        assertEquals(2L,selfServiceNodes("P007","'S05','S06'"));
        assertEquals(3L,selfServiceNodes("P008","'S03','S07','S08'"));
        assertEquals(1L,selfServiceNodes("P009","'S04'"));
        assertTrue(bool("select exists(select 1 from pg_constraint where conname='ck_p008_actual_leave_window')"));
        assertTrue(bool("select exists(select 1 from pg_constraint where conname='ck_p008_return_after_leave_start')"));
        assertTrue(bool("select exists(select 1 from pg_constraint where conname='ck_p009_timeoff_ledger_type')"));
    }

    private static long selfServiceNodes(String process,String quotedNodes) throws SQLException {
        return number("""
                select count(*)
                from workflow.wf_node n
                join workflow.wf_version v
                  on v.tenant_id=n.tenant_id and v.id=n.version_id
                join workflow.wf_definition d
                  on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where d.tenant_id='%s'
                  and d.process_code='%s'
                  and v.status='PUBLISHED'
                  and v.version_no=(
                    select max(v2.version_no)
                    from workflow.wf_version v2
                    where v2.tenant_id=v.tenant_id
                      and v2.definition_id=v.definition_id
                      and v2.status='PUBLISHED'
                      and not v2.is_deleted
                  )
                  and n.node_code in (%s)
                  and coalesce((n.actor_rule->>'allowInitiator')::boolean,false)
                  and not n.is_deleted and not v.is_deleted and not d.is_deleted
                """.formatted(TENANT,process,quotedNodes));
    }

    private static void seed() throws SQLException {
        execute("insert into org.organization(id,tenant_id,org_code,org_name,org_type,status) values('"+CENTER+"','"+TENANT+"','P10-HARDEN','Hardening Center','CENTER','ACTIVE')");
        employee(EMPLOYEE,"P10-HARDEN-EMP");employee(SHIFT_EMPLOYEE,"P10-HARDEN-SHIFT");employee(NO_FACT_EMPLOYEE,"P10-HARDEN-NONE");
        execute("insert into attendance.leave_request(id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,attendance_type,change_action,change_reason,duration_hours,start_at,end_at,quota_account_id,quota_amount,quota_reserved_at,handover_confirmed_at,decision,quota_settled_at,attendance_marked_at,leave_started_at,actual_start_at) values('"+LEAVE+"','"+TENANT+"','P008-HARDEN','销假/提前返岗/变更',7,'leave','"+CENTER+"','"+EMPLOYEE+"','ANNUAL_LEAVE','LEAVE','hardening',8,'"+START+"','"+END+"','ANNUAL-2026',1,now(),now(),'APPROVED',now(),now(),'"+START+"','"+START+"')");
        execute("insert into attendance.leave_request_item(id,tenant_id,master_id,field_code,item_seq,item_name) values('"+LEAVE_ITEM+"','"+TENANT+"','"+LEAVE+"','HANDOVER_ITEMS',1,'ordinary')");
        execute("insert into attendance.overtime_request(id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,attendance_type,duration_hours,start_at,end_at,emergency_fact) values('"+OVERTIME+"','"+TENANT+"','P009-HARDEN','必要性与任务校验',1,'overtime','"+CENTER+"','"+EMPLOYEE+"','OVERTIME',8,'"+START+"','"+END+"',false)");
        execute("insert into attendance.overtime_request_item(id,tenant_id,master_id,field_code,item_seq,item_name) values('"+OVERTIME_ITEM+"','"+TENANT+"','"+OVERTIME+"','ATTENDANCE_FACT',1,'ordinary')");
        execute("insert into attendance.shift_change_request(id,tenant_id,business_no,status,version_no,subject,owner_center_id,owner_employee_id,attendance_type,change_action,change_reason,content_version,duration_hours,start_at,end_at,period_or_course_no,target_employee_id) values('"+SHIFT+"','"+TENANT+"','P007-HARDEN','主管发布排班',3,'shift','"+CENTER+"','"+EMPLOYEE+"','排班','SCHEDULE','hardening','CURRENT',8,'"+START+"','"+END+"','2026-08-13','"+SHIFT_EMPLOYEE+"')");
    }

    private static void employee(UUID id,String no) throws SQLException {execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id) values('"+id+"','"+TENANT+"','"+no+"','Employee','ACTIVE',date '2026-08-13','"+CENTER+"')");}
    private static SQLException rejected(String sql){return assertThrows(SQLException.class,()->{try(Connection c=admin("sjg_oms");Statement s=c.createStatement()){s.execute(sql);}});}
    private static String message(SQLException e){StringBuilder b=new StringBuilder();for(SQLException x=e;x!=null;x=x.getNextException())if(x.getMessage()!=null)b.append(x.getMessage()).append(' ');return b.toString();}
    private static String string(String sql)throws SQLException{try(Connection c=admin("sjg_oms");Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    private static long number(String sql)throws SQLException{try(Connection c=admin("sjg_oms");Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private static boolean bool(String sql)throws SQLException{try(Connection c=admin("sjg_oms");Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getBoolean(1);}}
    private static void execute(String sql)throws SQLException{try(Connection c=admin("sjg_oms");Statement s=c.createStatement()){assertFalse(s.execute(sql));}}

    private static void migrate(String database,String generated,String overlay){
        List<String> locations=new ArrayList<>();locations.add("filesystem:"+root.resolve("technical-platform/database/flyway").resolve(generated));
        if(overlay!=null)locations.add("filesystem:"+root.resolve("technical-platform/database/flyway-overlays").resolve(overlay));
        Flyway f=Flyway.configure().dataSource(url(database),postgres.getUsername(),postgres.getPassword())
                .locations(locations.toArray(String[]::new)).placeholders(Map.of("sjg_tenant_id",TENANT.toString(),"sjg_tenant_code","P10_HARDEN","sjg_tenant_name","PHASE-10 Hardening Tenant")).cleanDisabled(true).load();
        assertTrue(f.migrate().success);f.validate();
    }
    private static Connection admin(String database)throws SQLException{return DriverManager.getConnection(url(database),postgres.getUsername(),postgres.getPassword());}
    private static String url(String database){String u=postgres.getJdbcUrl();int q=u.indexOf('?');String suffix=q>=0?u.substring(q):"";String base=q>=0?u.substring(0,q):u;return base.substring(0,base.lastIndexOf('/')+1)+database+suffix;}
    private static Path findRoot(){Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();while(p!=null){if(Files.isRegularFile(p.resolve("AGENT.md"))&&Files.isDirectory(p.resolve("Knowledge Base"))&&Files.isRegularFile(p.resolve("pom.xml")))return p;p=p.getParent();}throw new IllegalStateException("repository root not found");}
}
