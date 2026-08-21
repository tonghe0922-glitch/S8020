package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.ShiftChangeService.FormRef;
import cn.shangjingu.platform.workflow.ShiftChangeService.ShiftRecord;
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
public class JdbcShiftChangeRepository
        implements ShiftChangeService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcShiftChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return jdbc.query(
                        """
                        select v.id
                        from workflow.wf_version v
                        join workflow.wf_definition d
                          on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where v.tenant_id=?
                          and d.process_code='P007'
                          and d.enabled
                          and not d.is_deleted
                          and v.status='PUBLISHED'
                          and not v.is_deleted
                        order by v.version_no desc
                        limit 1
                        """,
                        (result, row) -> result.getObject(1, UUID.class),
                        tenantId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<FormRef> form(UUID tenantId) {
        return jdbc.query(
                        """
                        select id,version_no
                        from workflow.wf_form_definition
                        where tenant_id=?
                          and form_code='CTR-P007-F01'
                          and process_code='P007'
                          and node_code='S01'
                          and enabled
                          and not is_deleted
                        order by version_no desc
                        limit 1
                        """,
                        (result, row) ->
                                new FormRef(
                                        result.getObject(1, UUID.class),
                                        result.getInt(2)),
                        tenantId)
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permission, UUID orgId) {
        return jdbc.query(
                """
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r
                  on r.tenant_id=ur.tenant_id
                 and r.id=ur.role_id
                 and r.enabled
                 and not r.is_deleted
                join iam.role_permission rp
                  on rp.tenant_id=r.tenant_id
                 and rp.role_id=r.id
                 and not rp.is_deleted
                join iam.permission p
                  on p.tenant_id=rp.tenant_id
                 and p.id=rp.permission_id
                 and not p.is_deleted
                join iam.user_identity ui
                  on ui.tenant_id=ur.tenant_id
                 and ui.user_id=ur.user_id
                 and not ui.is_deleted
                 and (ur.identity_id is null or ur.identity_id=ui.id)
                where ur.tenant_id=?
                  and p.permission_code=?
                  and ui.org_id=?
                  and not ur.is_deleted
                  and ur.effective_start_at<=now()
                  and (ur.effective_end_at is null or ur.effective_end_at>now())
                """,
                (result, row) -> result.getObject(1, UUID.class),
                tenantId,
                permission,
                orgId);
    }

    @Override
    public boolean isActiveEmployeeInOrg(
            UUID tenantId, UUID orgId, UUID employeeId) {
        Boolean active =
                jdbc.queryForObject(
                        """
                        select exists(
                          select 1
                          from iam.user_identity ui
                          join org.employee e
                            on e.tenant_id=ui.tenant_id
                           and e.id=ui.employee_id
                          where ui.tenant_id=?
                            and ui.org_id=?
                            and ui.employee_id=?
                            and not ui.is_deleted
                            and e.employment_status='ACTIVE'
                            and not e.is_deleted
                            and ui.effective_start_at<=now()
                            and (
                              ui.effective_end_at is null
                              or ui.effective_end_at>now()
                            )
                        )
                        """,
                        Boolean.class,
                        tenantId,
                        orgId,
                        employeeId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public boolean hasOverlappingShift(
            UUID tenantId,
            UUID employeeId,
            Instant start,
            Instant end,
            UUID excludeId) {
        Boolean hit =
                jdbc.queryForObject(
                        """
                        select exists(
                          select 1
                          from attendance.shift_change_request r
                          where r.tenant_id=?
                            and r.id<>?
                            and not r.is_deleted
                            and (
                              r.target_employee_id=?
                              or r.replacement_employee_id=?
                            )
                            and r.closed_at is null
                            and tstzrange(r.start_at,r.end_at,'[)')
                                && tstzrange(?,?,'[)')
                        )
                        """,
                        Boolean.class,
                        tenantId,
                        excludeId,
                        employeeId,
                        employeeId,
                        timestamp(start),
                        timestamp(end));
        return Boolean.TRUE.equals(hit);
    }

    @Override
    public boolean hasAttendanceConflict(
            UUID tenantId,
            UUID employeeId,
            Instant start,
            Instant end) {
        Boolean hit =
                jdbc.queryForObject(
                        """
                        select (
                          exists(
                            select 1
                            from attendance.leave_request r
                            where r.tenant_id=?
                              and r.owner_employee_id=?
                              and not r.is_deleted
                              and r.closed_at is null
                              and tstzrange(r.start_at,r.end_at,'[)')
                                  && tstzrange(?,?,'[)')
                          )
                          or exists(
                            select 1
                            from attendance.overtime_request r
                            where r.tenant_id=?
                              and r.owner_employee_id=?
                              and not r.is_deleted
                              and r.closed_at is null
                              and tstzrange(r.start_at,r.end_at,'[)')
                                  && tstzrange(?,?,'[)')
                          )
                        )
                        """,
                        Boolean.class,
                        tenantId,
                        employeeId,
                        timestamp(start),
                        timestamp(end),
                        tenantId,
                        employeeId,
                        timestamp(start),
                        timestamp(end));
        return Boolean.TRUE.equals(hit);
    }

    @Override
    public void insert(ShiftRecord record, UUID actor) {
        int inserted =
                jdbc.update(
                        """
                        insert into attendance.shift_change_request(
                          id,tenant_id,business_no,status,version_no,
                          created_by,updated_by,source_channel,business_date,
                          subject,reason,priority,owner_center_id,owner_employee_id,
                          attendance_type,change_action,change_reason,content_version,
                          duration_hours,end_at,period_or_course_no,start_at,
                          template_code,target_employee_id,replacement_employee_id
                        )
                        values(
                          ?,?,?,?,0,?,?,'PC',current_date,
                          ?,?,'NORMAL',?,?,'排班',?,?,?,
                          ?,?,?,?, ?,?,?
                        )
                        """,
                        record.id(),
                        record.tenantId(),
                        record.businessNo(),
                        record.status(),
                        actor,
                        actor,
                        record.subject(),
                        record.reason(),
                        record.ownerCenterId(),
                        record.ownerEmployeeId(),
                        record.changeAction(),
                        record.changeReason(),
                        record.templateCode() == null
                                ? "CURRENT"
                                : record.templateCode(),
                        record.durationHours(),
                        timestamp(record.endAt()),
                        record.periodOrCourseNo(),
                        timestamp(record.startAt()),
                        record.templateCode(),
                        record.targetEmployeeId(),
                        record.replacementEmployeeId());
        if (inserted != 1) {
            throw new ProcessRejectedException(
                    "P007 canonical shift record insert failed");
        }
    }

    @Override
    public int bindAndMove(
            UUID tenantId,
            UUID id,
            int version,
            UUID workflowId,
            String status,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set workflow_instance_id=?,
                    status=?,
                    version_no=version_no+1,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and version_no=?
                  and not is_deleted
                """,
                workflowId,
                status,
                actor,
                tenantId,
                id,
                version);
    }

    @Override
    public int moveStatus(
            UUID tenantId,
            UUID id,
            int version,
            String status,
            Instant closed,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set status=?,
                    closed_at=coalesce(?,closed_at),
                    actual_end_at=coalesce(?,actual_end_at),
                    version_no=version_no+1,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and version_no=?
                  and not is_deleted
                """,
                status,
                timestamp(closed),
                timestamp(closed),
                actor,
                tenantId,
                id,
                version);
    }

    @Override
    public int markValidated(
            UUID tenantId,
            UUID id,
            BigDecimal continuousHours,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set qualification_checked_at=now(),
                    continuous_work_hours=?,
                    conflict_checked_at=now(),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and qualification_checked_at is null
                  and not is_deleted
                """,
                continuousHours,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markPublished(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set published_at=coalesce(published_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and qualification_checked_at is not null
                  and published_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markConfirmed(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set employee_confirmed_at=coalesce(employee_confirmed_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and target_employee_id=?
                  and published_at is not null
                  and employee_confirmed_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id,
                actor);
    }

    @Override
    public int setReplacement(
            UUID tenantId,
            UUID id,
            UUID replacement,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set replacement_employee_id=?,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and target_employee_id=?
                  and employee_confirmed_at is not null
                  and approved_at is null
                  and not is_deleted
                """,
                replacement,
                actor,
                tenantId,
                id,
                actor);
    }

    @Override
    public int markApproved(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set approved_at=coalesce(approved_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and employee_confirmed_at is not null
                  and approved_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markDependencies(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set attendance_linked_at=coalesce(attendance_linked_at,now()),
                    catering_linked_at=coalesce(catering_linked_at,now()),
                    shuttle_linked_at=coalesce(shuttle_linked_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and approved_at is not null
                  and attendance_linked_at is null
                  and catering_linked_at is null
                  and shuttle_linked_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markDayClosed(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.shift_change_request
                set day_closed_at=coalesce(day_closed_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and attendance_linked_at is not null
                  and catering_linked_at is not null
                  and shuttle_linked_at is not null
                  and day_closed_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public Optional<ShiftRecord> find(UUID tenantId, UUID id) {
        return jdbc.query(
                        select(
                                """
                                where r.tenant_id=?
                                  and r.id=?
                                  and not r.is_deleted
                                """),
                        (result, row) -> map(result),
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<ShiftRecord> list(UUID tenantId) {
        return jdbc.query(
                select(
                        """
                        where r.tenant_id=?
                          and not r.is_deleted
                        order by r.created_at desc,r.id desc
                        """),
                (result, row) -> map(result),
                tenantId);
    }

    private String select(String suffix) {
        return """
               select r.id,r.tenant_id,r.business_no,r.workflow_instance_id,
                      r.status,r.version_no,r.subject,r.reason,
                      r.owner_center_id,r.owner_employee_id,
                      r.target_employee_id,r.replacement_employee_id,
                      r.change_action,r.change_reason,r.template_code,
                      r.period_or_course_no,r.start_at,r.end_at,r.duration_hours,
                      r.qualification_checked_at,r.continuous_work_hours,
                      r.conflict_checked_at,r.published_at,
                      r.employee_confirmed_at,r.approved_at,
                      r.attendance_linked_at,r.catering_linked_at,
                      r.shuttle_linked_at,r.day_closed_at,r.updated_at,
                      wi.instance_no workflow_instance_no,
                      wi.current_node_code
               from attendance.shift_change_request r
               left join workflow.wf_instance wi
                 on wi.tenant_id=r.tenant_id
                and wi.id=r.workflow_instance_id
                and not wi.is_deleted
               """
                + suffix;
    }

    private ShiftRecord map(ResultSet result) throws SQLException {
        return new ShiftRecord(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getString("business_no"),
                result.getObject("workflow_instance_id", UUID.class),
                result.getString("workflow_instance_no"),
                result.getString("current_node_code"),
                result.getString("status"),
                result.getInt("version_no"),
                result.getString("subject"),
                result.getString("reason"),
                result.getObject("owner_center_id", UUID.class),
                result.getObject("owner_employee_id", UUID.class),
                result.getObject("target_employee_id", UUID.class),
                result.getObject("replacement_employee_id", UUID.class),
                result.getString("change_action"),
                result.getString("change_reason"),
                result.getString("template_code"),
                result.getString("period_or_course_no"),
                instant(result, "start_at"),
                instant(result, "end_at"),
                result.getBigDecimal("duration_hours"),
                instant(result, "qualification_checked_at"),
                result.getBigDecimal("continuous_work_hours"),
                instant(result, "conflict_checked_at"),
                instant(result, "published_at"),
                instant(result, "employee_confirmed_at"),
                instant(result, "approved_at"),
                instant(result, "attendance_linked_at"),
                instant(result, "catering_linked_at"),
                instant(result, "shuttle_linked_at"),
                instant(result, "day_closed_at"),
                instant(result, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException {
        Object value = result.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.ZonedDateTime) value).toInstant();
    }
}
