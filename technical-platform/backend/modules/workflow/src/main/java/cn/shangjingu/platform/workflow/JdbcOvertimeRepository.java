package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.workflow.OvertimeService.FormRef;
import cn.shangjingu.platform.workflow.OvertimeService.OvertimeRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOvertimeRepository implements OvertimeService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcOvertimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID t) {
        return jdbc.query("""
                select v.id
                from workflow.wf_version v
                join workflow.wf_definition d
                  on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where v.tenant_id=?
                  and d.process_code='P009'
                  and d.enabled and not d.is_deleted
                  and v.status='PUBLISHED' and not v.is_deleted
                order by v.version_no desc
                limit 1
                """, (rs,n)->rs.getObject(1,UUID.class), t).stream().findFirst();
    }

    @Override
    public Optional<FormRef> form(UUID t) {
        return jdbc.query(
                "select id,version_no from workflow.wf_form_definition where tenant_id=? and form_code='CTR-P009-F01' and process_code='P009' and node_code='S01' and enabled and not is_deleted order by version_no desc limit 1",
                (rs,n)->new FormRef(rs.getObject(1,UUID.class),rs.getInt(2)), t).stream().findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(UUID t,String permission,UUID org) {
        return jdbc.query("""
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r on r.tenant_id=ur.tenant_id and r.id=ur.role_id and r.enabled and not r.is_deleted
                join iam.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
                join iam.permission p on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                join iam.user_identity ui on ui.tenant_id=ur.tenant_id and ui.user_id=ur.user_id and not ui.is_deleted
                  and (ur.identity_id is null or ur.identity_id=ui.id)
                where ur.tenant_id=? and p.permission_code=? and ui.org_id=? and not ur.is_deleted
                  and ur.effective_start_at<=now() and (ur.effective_end_at is null or ur.effective_end_at>now())
                """, (rs,n)->rs.getObject(1,UUID.class), t, permission, org);
    }

    @Override
    public boolean hasPlanningConflict(UUID t,UUID employee,Instant s,Instant e) {
        Boolean hit=jdbc.queryForObject("""
                select (
                  exists(
                    select 1 from attendance.leave_request r
                    where r.tenant_id=? and r.owner_employee_id=? and not r.is_deleted and r.closed_at is null
                      and tstzrange(r.start_at,r.end_at,'[)') && tstzrange(?,?,'[)')
                  )
                  or exists(
                    select 1 from attendance.overtime_request r
                    where r.tenant_id=? and r.owner_employee_id=? and not r.is_deleted and r.closed_at is null
                      and tstzrange(r.start_at,r.end_at,'[)') && tstzrange(?,?,'[)')
                  )
                )
                """, Boolean.class, t,employee,ts(s),ts(e), t,employee,ts(s),ts(e));
        return Boolean.TRUE.equals(hit);
    }

    @Override
    public boolean hasLeaveConflict(UUID t,UUID employee,Instant s,Instant e) {
        Boolean hit=jdbc.queryForObject(
                "select exists(select 1 from attendance.leave_request r where r.tenant_id=? and r.owner_employee_id=? and not r.is_deleted and r.closed_at is null and tstzrange(r.start_at,r.end_at,'[)') && tstzrange(?,?,'[)'))",
                Boolean.class,t,employee,ts(s),ts(e));
        return Boolean.TRUE.equals(hit);
    }

    @Override
    public void insert(OvertimeRecord r,UUID actor) {
        jdbc.update("""
                insert into attendance.overtime_request(
                  id,tenant_id,business_no,status,version_no,created_by,updated_by,source_channel,business_date,
                  subject,reason,priority,owner_center_id,owner_employee_id,attendance_type,duration_hours,end_at,start_at,emergency_fact
                )
                values(?,?,?, ?,0,?,?,'PC',current_date,?,?, 'NORMAL',?,?,?,?,?,?,?)
                """,
                r.id(),r.tenantId(),r.businessNo(),r.status(),actor,actor,r.subject(),r.reason(),r.ownerCenterId(),r.ownerEmployeeId(),
                r.attendanceType(),r.durationHours(),ts(r.endAt()),ts(r.startAt()),r.emergencyFact());
    }

    @Override
    public int bindAndMove(UUID t,UUID id,int v,UUID wf,String status,UUID actor) {
        return jdbc.update("update attendance.overtime_request set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and version_no=? and not is_deleted",wf,status,actor,t,id,v);
    }

    @Override
    public int moveStatus(UUID t,UUID id,int v,String status,Instant closed,UUID actor) {
        return jdbc.update("update attendance.overtime_request set status=?,closed_at=coalesce(?,closed_at),version_no=version_no+1,updated_by=?,updated_at=now() where tenant_id=? and id=? and version_no=? and not is_deleted",status,ts(closed),actor,t,id,v);
    }

    @Override
    public int markNecessity(UUID t,UUID id,UUID actor) {
        return jdbc.update("update attendance.overtime_request set necessity_checked_at=coalesce(necessity_checked_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and supervisor_decision is null and not is_deleted",actor,t,id);
    }

    @Override
    public int markDecision(UUID t,UUID id,String decision,UUID actor) {
        return jdbc.update("""
                update attendance.overtime_request
                   set supervisor_decision=?,
                       supervisor_approved_at=case when ?='APPROVED' then coalesce(supervisor_approved_at,now()) else supervisor_approved_at end,
                       supervisor_rejected_at=case when ?='REJECTED' then coalesce(supervisor_rejected_at,now()) else supervisor_rejected_at end,
                       updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and necessity_checked_at is not null and supervisor_decision is null and not is_deleted
                """, decision,decision,decision,actor,t,id);
    }

    @Override
    public int recordActual(UUID t,UUID id,Instant s,Instant e,BigDecimal hours,String summary,UUID actor) {
        return jdbc.update("update attendance.overtime_request set actual_start_at=?,actual_end_at=?,actual_duration_hours=?,actual_attendance_summary=?,actual_fact_recorded_at=coalesce(actual_fact_recorded_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and owner_employee_id=? and supervisor_decision='APPROVED' and actual_fact_recorded_at is null and not is_deleted",ts(s),ts(e),hours,summary,actor,t,id,actor);
    }

    @Override
    public int acceptResult(UUID t,UUID id,String result,UUID actor) {
        return jdbc.update("update attendance.overtime_request set result_summary=?,result_accepted_at=coalesce(result_accepted_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and actual_fact_recorded_at is not null and not is_deleted",result,actor,t,id);
    }

    @Override
    public int markHrReviewed(UUID t,UUID id,UUID actor) {
        return jdbc.update("update attendance.overtime_request set hr_reviewed_at=coalesce(hr_reviewed_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and result_accepted_at is not null and not is_deleted",actor,t,id);
    }

    @Override
    public int setCompensationPlan(UUID t,UUID id,String plan,BigDecimal wage,String account,BigDecimal timeOff,UUID actor) {
        return jdbc.update("update attendance.overtime_request set compensation_plan=?,actual_amount=?,quota_account_id=?,quota_amount=?,compensation_planned_at=coalesce(compensation_planned_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and hr_reviewed_at is not null and compensation_plan is null and not is_deleted",plan,wage,account,timeOff,actor,t,id);
    }

    @Override
    public int ackPayroll(UUID t,UUID id,String ref,UUID actor) {
        return jdbc.update("update attendance.overtime_request set payroll_reference=?,payroll_receipt_at=coalesce(payroll_receipt_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and compensation_planned_at is not null and not is_deleted",ref,actor,t,id);
    }

    @Override
    public int archive(UUID t,UUID id,UUID actor) {
        return jdbc.update("update attendance.overtime_request set archived_at=coalesce(archived_at,now()),updated_by=?,updated_at=now() where tenant_id=? and id=? and payroll_receipt_at is not null and not is_deleted",actor,t,id);
    }

    @Override
    public void appendTimeOffLedger(UUID t,UUID id,String type,BigDecimal amount,String note,UUID actor) {
        jdbc.queryForObject("select id from attendance.overtime_request where tenant_id=? and id=? and not is_deleted for update",UUID.class,t,id);
        Integer seq=jdbc.queryForObject("select coalesce(max(item_seq),0)+1 from attendance.overtime_request_item where tenant_id=? and master_id=? and field_code='TIME_OFF_LEDGER' and not is_deleted",Integer.class,t,id);
        jdbc.update("""
                insert into attendance.overtime_request_item(
                  id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_key,item_name,item_value_text,
                  item_value_number,related_object_type,related_object_id,quantity,sort_no
                )
                values(gen_random_uuid(),?,?,?,?,'TIME_OFF_LEDGER',?,?,?, ?,?,'OVERTIME_REQUEST',?,?,?)
                """, t,actor,actor,id,seq,type,"P009 time-off "+type,note,amount,id,amount,seq);
    }

    @Override
    public Optional<OvertimeRecord> find(UUID t,UUID id) {
        return jdbc.query(select("where r.tenant_id=? and r.id=? and not r.is_deleted"),(rs,n)->map(rs),t,id).stream().findFirst();
    }

    @Override
    public List<OvertimeRecord> list(UUID t) {
        return jdbc.query(select("where r.tenant_id=? and not r.is_deleted order by r.created_at desc,r.id desc"),(rs,n)->map(rs),t);
    }

    private String select(String suffix) {
        return """
                select r.id,r.tenant_id,r.business_no,r.workflow_instance_id,r.status,r.version_no,r.subject,r.reason,
                       r.owner_center_id,r.owner_employee_id,r.attendance_type,r.start_at,r.end_at,r.duration_hours,r.emergency_fact,
                       r.necessity_checked_at,r.supervisor_decision,r.supervisor_approved_at,r.supervisor_rejected_at,
                       r.actual_start_at,r.actual_end_at,r.actual_duration_hours,r.actual_attendance_summary,r.actual_fact_recorded_at,
                       r.result_summary,r.result_accepted_at,r.hr_reviewed_at,r.compensation_plan,r.actual_amount,r.quota_account_id,
                       r.quota_amount,r.compensation_planned_at,r.payroll_reference,r.payroll_receipt_at,r.archived_at,r.closed_at,r.updated_at,
                       wi.instance_no workflow_instance_no,wi.current_node_code
                  from attendance.overtime_request r
                  left join workflow.wf_instance wi
                    on wi.tenant_id=r.tenant_id and wi.id=r.workflow_instance_id and not wi.is_deleted
                """ + suffix;
    }

    private OvertimeRecord map(ResultSet rs)throws SQLException {
        return new OvertimeRecord(
                rs.getObject("id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("business_no"),
                rs.getObject("workflow_instance_id",UUID.class),rs.getString("workflow_instance_no"),rs.getString("current_node_code"),
                rs.getString("status"),rs.getInt("version_no"),rs.getString("subject"),rs.getString("reason"),
                rs.getObject("owner_center_id",UUID.class),rs.getObject("owner_employee_id",UUID.class),rs.getString("attendance_type"),
                instant(rs,"start_at"),instant(rs,"end_at"),rs.getBigDecimal("duration_hours"),rs.getBoolean("emergency_fact"),
                instant(rs,"necessity_checked_at"),rs.getString("supervisor_decision"),instant(rs,"supervisor_approved_at"),
                instant(rs,"supervisor_rejected_at"),instant(rs,"actual_start_at"),instant(rs,"actual_end_at"),
                rs.getBigDecimal("actual_duration_hours"),rs.getString("actual_attendance_summary"),instant(rs,"actual_fact_recorded_at"),
                rs.getString("result_summary"),instant(rs,"result_accepted_at"),instant(rs,"hr_reviewed_at"),rs.getString("compensation_plan"),
                rs.getBigDecimal("actual_amount"),rs.getString("quota_account_id"),rs.getBigDecimal("quota_amount"),
                instant(rs,"compensation_planned_at"),rs.getString("payroll_reference"),instant(rs,"payroll_receipt_at"),
                instant(rs,"archived_at"),instant(rs,"closed_at"),instant(rs,"updated_at"));
    }

    private static Timestamp ts(Instant v){return v==null?null:Timestamp.from(v);}
    private static Instant instant(ResultSet rs,String c)throws SQLException{Object v=rs.getObject(c);if(v==null)return null;if(v instanceof OffsetDateTime o)return o.toInstant();if(v instanceof Timestamp t)return t.toInstant();return ((java.time.ZonedDateTime)v).toInstant();}
}
