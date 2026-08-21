package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.LeaveService.FormRef;
import cn.shangjingu.platform.workflow.LeaveService.LeaveRecord;
import cn.shangjingu.platform.workflow.LeaveService.LedgerEntry;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLeaveRepository implements LeaveService.Repository {
    private static final Set<String> LEDGER_TYPES =
            Set.of("RESERVE", "DEDUCT", "RELEASE", "ADJUST");

    private final JdbcTemplate jdbc;

    public JdbcLeaveRepository(JdbcTemplate jdbc) {
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
                          and d.process_code='P008'
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
                          and form_code='CTR-P008-F01'
                          and process_code='P008'
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
    public boolean hasTimeConflict(
            UUID tenantId,
            UUID employeeId,
            Instant start,
            Instant end) {
        Boolean conflict =
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
                            from attendance.shift_change_request r
                            where r.tenant_id=?
                              and not r.is_deleted
                              and r.closed_at is null
                              and (
                                r.target_employee_id=?
                                or r.replacement_employee_id=?
                              )
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
                        employeeId,
                        timestamp(start),
                        timestamp(end),
                        tenantId,
                        employeeId,
                        timestamp(start),
                        timestamp(end));
        return Boolean.TRUE.equals(conflict);
    }

    @Override
    public void insert(LeaveRecord record, UUID actor) {
        int inserted =
                jdbc.update(
                        """
                        insert into attendance.leave_request(
                          id,tenant_id,business_no,status,version_no,
                          created_by,updated_by,source_channel,business_date,
                          subject,reason,priority,owner_center_id,owner_employee_id,
                          attendance_type,change_action,change_reason,duration_hours,
                          end_at,quota_account_id,quota_amount,start_at,
                          handover_agent_id,known_impact
                        )
                        values(
                          ?,?,?,?,0,?,?,'PC',current_date,
                          ?,?,'NORMAL',?,? ,?,'LEAVE',?,
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
                        record.attendanceType(),
                        record.reason() == null
                                ? "LEAVE_REQUEST"
                                : record.reason(),
                        record.durationHours(),
                        timestamp(record.endAt()),
                        record.quotaAccountId(),
                        record.quotaAmount(),
                        timestamp(record.startAt()),
                        record.handoverAgentId(),
                        record.knownImpact());
        if (inserted != 1) {
            throw new ProcessRejectedException(
                    "P008 canonical leave request insert failed");
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
                update attendance.leave_request
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
            Instant closedAt,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
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
                timestamp(closedAt),
                timestamp(closedAt),
                actor,
                tenantId,
                id,
                version);
    }

    @Override
    public int markQuotaReserved(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set quota_reserved_at=coalesce(quota_reserved_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and quota_amount>0
                  and quota_account_id is not null
                  and quota_reserved_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markHandover(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set handover_confirmed_at=coalesce(handover_confirmed_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and owner_employee_id=?
                  and quota_reserved_at is not null
                  and handover_confirmed_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id,
                actor);
    }

    @Override
    public int markDecision(
            UUID tenantId, UUID id, String decision, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set decision=?,
                    approved_at=case
                      when ?='APPROVED' then coalesce(approved_at,now())
                      else approved_at
                    end,
                    rejected_at=case
                      when ?='REJECTED' then coalesce(rejected_at,now())
                      else rejected_at
                    end,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and handover_confirmed_at is not null
                  and decision is null
                  and not is_deleted
                """,
                decision,
                decision,
                decision,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markQuotaSettled(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set quota_settled_at=coalesce(quota_settled_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and decision is not null
                  and quota_reserved_at is not null
                  and quota_settled_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markAttendance(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set attendance_marked_at=coalesce(attendance_marked_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and decision='APPROVED'
                  and quota_settled_at is not null
                  and attendance_marked_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markLeaveStarted(
            UUID tenantId, UUID id, Instant actualAt, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set leave_started_at=coalesce(leave_started_at,?),
                    actual_start_at=coalesce(actual_start_at,?),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and owner_employee_id=?
                  and attendance_marked_at is not null
                  and leave_started_at is null
                  and not is_deleted
                """,
                timestamp(actualAt),
                timestamp(actualAt),
                actor,
                tenantId,
                id,
                actor);
    }

    @Override
    public int markReturned(
            UUID tenantId, UUID id, Instant actualAt, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set returned_at=coalesce(returned_at,?),
                    actual_end_at=coalesce(actual_end_at,?),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and owner_employee_id=?
                  and leave_started_at is not null
                  and returned_at is null
                  and ?>=leave_started_at
                  and not is_deleted
                """,
                timestamp(actualAt),
                timestamp(actualAt),
                actor,
                tenantId,
                id,
                actor,
                timestamp(actualAt));
    }

    @Override
    public int markQuotaAdjusted(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set quota_adjusted_at=coalesce(quota_adjusted_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and returned_at is not null
                  and quota_adjusted_at is null
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
                update attendance.leave_request
                set day_closed_at=coalesce(day_closed_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and quota_adjusted_at is not null
                  and day_closed_at is null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public void appendLedger(
            UUID tenantId,
            UUID id,
            String type,
            BigDecimal amount,
            String note,
            UUID actor) {
        validateLedgerEntry(type, amount);
        jdbc.queryForObject(
                """
                select id
                from attendance.leave_request
                where tenant_id=?
                  and id=?
                  and not is_deleted
                for update
                """,
                UUID.class,
                tenantId,
                id);
        Integer next =
                jdbc.queryForObject(
                        """
                        select coalesce(max(item_seq),0)+1
                        from attendance.leave_request_item
                        where tenant_id=?
                          and master_id=?
                          and field_code='QUOTA_LEDGER'
                          and not is_deleted
                        """,
                        Integer.class,
                        tenantId,
                        id);
        int inserted =
                jdbc.update(
                        """
                        insert into attendance.leave_request_item(
                          id,tenant_id,created_by,updated_by,master_id,
                          field_code,item_seq,item_key,item_name,item_value_text,
                          item_value_number,related_object_type,related_object_id,
                          quantity,sort_no
                        )
                        values(
                          gen_random_uuid(),?,?,?,?,'QUOTA_LEDGER',
                          ?,?,?,?,?,'LEAVE_REQUEST',?,?,?
                        )
                        """,
                        tenantId,
                        actor,
                        actor,
                        id,
                        next,
                        type,
                        "P008 quota " + type,
                        note,
                        amount,
                        id,
                        amount,
                        next);
        if (inserted != 1) {
            throw new ProcessRejectedException(
                    "P008 quota ledger append failed");
        }
    }

    @Override
    public Optional<LeaveRecord> find(UUID tenantId, UUID id) {
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
    public List<LeaveRecord> list(UUID tenantId) {
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

    @Override
    public List<LedgerEntry> ledger(UUID tenantId) {
        return jdbc.query(
                """
                select i.id,i.master_id,r.business_no,
                       r.owner_center_id,r.owner_employee_id,
                       i.item_seq,i.item_key,i.item_value_number,
                       i.item_value_text,i.created_at
                from attendance.leave_request_item i
                join attendance.leave_request r
                  on r.tenant_id=i.tenant_id
                 and r.id=i.master_id
                 and not r.is_deleted
                where i.tenant_id=?
                  and i.field_code='QUOTA_LEDGER'
                  and not i.is_deleted
                order by i.created_at,i.item_seq,i.id
                """,
                (result, row) ->
                        new LedgerEntry(
                                result.getObject("id", UUID.class),
                                result.getObject("master_id", UUID.class),
                                result.getString("business_no"),
                                result.getObject(
                                        "owner_center_id", UUID.class),
                                result.getObject(
                                        "owner_employee_id", UUID.class),
                                result.getInt("item_seq"),
                                result.getString("item_key"),
                                result.getBigDecimal("item_value_number"),
                                result.getString("item_value_text"),
                                instant(result, "created_at")),
                tenantId);
    }

    private static void validateLedgerEntry(
            String type, BigDecimal amount) {
        if (!LEDGER_TYPES.contains(type)) {
            throw new ProcessRejectedException(
                    "P008 quota ledger entry type is invalid");
        }
        if (amount == null) {
            throw new ProcessRejectedException(
                    "P008 quota ledger amount is required");
        }
        if ("ADJUST".equals(type)) {
            if (amount.signum() == 0) {
                throw new ProcessRejectedException(
                        "P008 quota adjustment must be non-zero");
            }
        } else if (amount.signum() <= 0) {
            throw new ProcessRejectedException(
                    "P008 quota ledger amount must be positive");
        }
    }

    private String select(String suffix) {
        return """
               select r.id,r.tenant_id,r.business_no,r.workflow_instance_id,
                      r.status,r.version_no,r.subject,r.reason,
                      r.owner_center_id,r.owner_employee_id,r.attendance_type,
                      r.start_at,r.end_at,r.duration_hours,
                      r.quota_account_id,r.quota_amount,
                      r.handover_agent_id,r.known_impact,
                      r.quota_reserved_at,r.handover_confirmed_at,
                      r.decision,r.approved_at,r.rejected_at,
                      r.quota_settled_at,r.attendance_marked_at,
                      r.leave_started_at,r.returned_at,
                      r.quota_adjusted_at,r.day_closed_at,
                      r.closed_at,r.updated_at,
                      wi.instance_no workflow_instance_no,
                      wi.current_node_code
               from attendance.leave_request r
               left join workflow.wf_instance wi
                 on wi.tenant_id=r.tenant_id
                and wi.id=r.workflow_instance_id
                and not wi.is_deleted
               """
                + suffix;
    }

    private LeaveRecord map(ResultSet result) throws SQLException {
        return new LeaveRecord(
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
                result.getString("attendance_type"),
                instant(result, "start_at"),
                instant(result, "end_at"),
                result.getBigDecimal("duration_hours"),
                result.getString("quota_account_id"),
                result.getBigDecimal("quota_amount"),
                result.getString("handover_agent_id"),
                result.getString("known_impact"),
                instant(result, "quota_reserved_at"),
                instant(result, "handover_confirmed_at"),
                result.getString("decision"),
                instant(result, "approved_at"),
                instant(result, "rejected_at"),
                instant(result, "quota_settled_at"),
                instant(result, "attendance_marked_at"),
                instant(result, "leave_started_at"),
                instant(result, "returned_at"),
                instant(result, "quota_adjusted_at"),
                instant(result, "day_closed_at"),
                instant(result, "closed_at"),
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
