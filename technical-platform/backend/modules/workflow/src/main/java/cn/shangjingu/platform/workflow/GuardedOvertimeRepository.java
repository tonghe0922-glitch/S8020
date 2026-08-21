package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Production fail-closed adapter for P009 planning conflicts and time-off ledger facts. */
@Primary
@Repository
public class GuardedOvertimeRepository implements OvertimeService.Repository {
    private final JdbcOvertimeRepository delegate;
    private final JdbcTemplate jdbc;

    public GuardedOvertimeRepository(
            JdbcOvertimeRepository delegate, JdbcTemplate jdbc) {
        this.delegate = delegate;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return delegate.workflowVersion(tenantId);
    }

    @Override
    public Optional<OvertimeService.FormRef> form(UUID tenantId) {
        return delegate.form(tenantId);
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permission, UUID orgId) {
        return delegate.permissionCandidates(tenantId, permission, orgId);
    }

    @Override
    public boolean hasPlanningConflict(
            UUID tenantId, UUID employeeId, Instant start, Instant end) {
        if (delegate.hasPlanningConflict(tenantId, employeeId, start, end)) {
            return true;
        }
        Boolean conflict =
                jdbc.queryForObject(
                        """
                        select exists(
                          select 1 from attendance.shift_change_request r
                          where r.tenant_id=? and not r.is_deleted and r.closed_at is null
                            and (r.target_employee_id=? or r.replacement_employee_id=?)
                            and tstzrange(r.start_at,r.end_at,'[)')
                                && tstzrange(?,?,'[)'))
                        """,
                        Boolean.class,
                        tenantId,
                        employeeId,
                        employeeId,
                        timestamp(start),
                        timestamp(end));
        return Boolean.TRUE.equals(conflict);
    }

    @Override
    public boolean hasLeaveConflict(
            UUID tenantId, UUID employeeId, Instant start, Instant end) {
        return delegate.hasLeaveConflict(tenantId, employeeId, start, end);
    }

    @Override
    public void insert(OvertimeService.OvertimeRecord record, UUID actor) {
        delegate.insert(record, actor);
    }

    @Override
    public int bindAndMove(
            UUID tenantId,
            UUID id,
            int version,
            UUID workflowId,
            String status,
            UUID actor) {
        return required(
                delegate.bindAndMove(tenantId, id, version, workflowId, status, actor),
                "workflow binding");
    }

    @Override
    public int moveStatus(
            UUID tenantId,
            UUID id,
            int version,
            String status,
            Instant closedAt,
            UUID actor) {
        return required(
                delegate.moveStatus(tenantId, id, version, status, closedAt, actor),
                "workflow projection transition");
    }

    @Override
    public int markNecessity(UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markNecessity(tenantId, id, actor),
                "necessity validation fact");
    }

    @Override
    public int markDecision(
            UUID tenantId, UUID id, String decision, UUID actor) {
        return required(
                delegate.markDecision(tenantId, id, decision, actor),
                "supervisor decision fact");
    }

    @Override
    public int recordActual(
            UUID tenantId,
            UUID id,
            Instant start,
            Instant end,
            BigDecimal hours,
            String summary,
            UUID actor) {
        return required(
                delegate.recordActual(tenantId, id, start, end, hours, summary, actor),
                "actual labor fact");
    }

    @Override
    public int acceptResult(
            UUID tenantId, UUID id, String result, UUID actor) {
        return required(
                delegate.acceptResult(tenantId, id, result, actor),
                "result acceptance fact");
    }

    @Override
    public int markHrReviewed(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markHrReviewed(tenantId, id, actor), "HR review fact");
    }

    @Override
    public int setCompensationPlan(
            UUID tenantId,
            UUID id,
            String plan,
            BigDecimal wage,
            String quotaAccount,
            BigDecimal timeOff,
            UUID actor) {
        return required(
                delegate.setCompensationPlan(
                        tenantId, id, plan, wage, quotaAccount, timeOff, actor),
                "compensation plan fact");
    }

    @Override
    public int ackPayroll(
            UUID tenantId, UUID id, String reference, UUID actor) {
        return required(
                delegate.ackPayroll(tenantId, id, reference, actor),
                "payroll receipt fact");
    }

    @Override
    public int archive(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.archive(tenantId, id, actor), "archive fact");
    }

    @Override
    public void appendTimeOffLedger(
            UUID tenantId,
            UUID id,
            String entryType,
            BigDecimal amount,
            String note,
            UUID actor) {
        if (!"GRANT".equals(entryType)) {
            throw new ProcessRejectedException(
                    "P009 time-off ledger entry type is invalid");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ProcessRejectedException(
                    "P009 time-off ledger amount must be positive");
        }
        delegate.appendTimeOffLedger(tenantId, id, entryType, amount, note, actor);
    }

    @Override
    public Optional<OvertimeService.OvertimeRecord> find(UUID tenantId, UUID id) {
        return delegate.find(tenantId, id);
    }

    @Override
    public List<OvertimeService.OvertimeRecord> list(UUID tenantId) {
        return delegate.list(tenantId);
    }

    private static int required(int updated, String operation) {
        if (updated != 1) {
            throw new ProcessRejectedException("P009 " + operation + " failed closed");
        }
        return updated;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
