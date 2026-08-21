package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Production fail-closed adapter for P007 canonical facts and cross-process time conflicts. */
@Primary
@Repository
public class GuardedShiftChangeRepository implements ShiftChangeService.Repository {
    private final JdbcShiftChangeRepository delegate;
    private final JdbcTemplate jdbc;
    private final P007ShiftChangeInsertWriter insertWriter;

    public GuardedShiftChangeRepository(
            JdbcShiftChangeRepository delegate, JdbcTemplate jdbc) {
        this(delegate, jdbc, new P007ShiftChangeInsertWriter(jdbc));
    }

    @Autowired
    public GuardedShiftChangeRepository(
            JdbcShiftChangeRepository delegate,
            JdbcTemplate jdbc,
            P007ShiftChangeInsertWriter insertWriter) {
        this.delegate = delegate;
        this.jdbc = jdbc;
        this.insertWriter = insertWriter;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return delegate.workflowVersion(tenantId);
    }

    @Override
    public Optional<ShiftChangeService.FormRef> form(UUID tenantId) {
        return delegate.form(tenantId);
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permission, UUID orgId) {
        return delegate.permissionCandidates(tenantId, permission, orgId);
    }

    @Override
    public boolean isActiveEmployeeInOrg(
            UUID tenantId, UUID orgId, UUID employeeId) {
        return delegate.isActiveEmployeeInOrg(tenantId, orgId, employeeId);
    }

    @Override
    public boolean hasOverlappingShift(
            UUID tenantId,
            UUID employeeId,
            Instant start,
            Instant end,
            UUID excludeId) {
        if (delegate.hasOverlappingShift(tenantId, employeeId, start, end, excludeId)) {
            return true;
        }
        Boolean conflict =
                jdbc.queryForObject(
                        """
                        select (
                          exists(select 1 from attendance.leave_request r
                            where r.tenant_id=? and r.owner_employee_id=?
                              and not r.is_deleted and r.closed_at is null
                              and tstzrange(r.start_at,r.end_at,'[)')
                                  && tstzrange(?,?,'[)'))
                          or exists(select 1 from attendance.overtime_request r
                            where r.tenant_id=? and r.owner_employee_id=?
                              and not r.is_deleted and r.closed_at is null
                              and tstzrange(r.start_at,r.end_at,'[)')
                                  && tstzrange(?,?,'[)'))
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
        return Boolean.TRUE.equals(conflict);
    }

    @Override
    public boolean hasAttendanceConflict(
            UUID tenantId, UUID employeeId, Instant start, Instant end) {
        return delegate.hasAttendanceConflict(tenantId, employeeId, start, end);
    }

    @Override
    public void insert(ShiftChangeService.ShiftRecord record, UUID actor) {
        insertWriter.insert(record, actor);
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
    public int markValidated(
            UUID tenantId, UUID id, BigDecimal continuousHours, UUID actor) {
        return required(
                delegate.markValidated(tenantId, id, continuousHours, actor),
                "qualification/continuous-work validation fact");
    }

    @Override
    public int markPublished(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markPublished(tenantId, id, actor), "schedule publication fact");
    }

    @Override
    public int markConfirmed(UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markConfirmed(tenantId, id, actor), "employee confirmation fact");
    }

    @Override
    public int setReplacement(
            UUID tenantId, UUID id, UUID replacement, UUID actor) {
        return required(
                delegate.setReplacement(tenantId, id, replacement, actor),
                "shift-change request fact");
    }

    @Override
    public int markApproved(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markApproved(tenantId, id, actor), "change approval fact");
    }

    @Override
    public int markDependencies(UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markDependencies(tenantId, id, actor),
                "attendance/catering/shuttle linkage fact");
    }

    @Override
    public int markDayClosed(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markDayClosed(tenantId, id, actor), "day-close fact");
    }

    @Override
    public Optional<ShiftChangeService.ShiftRecord> find(UUID tenantId, UUID id) {
        return delegate.find(tenantId, id);
    }

    @Override
    public List<ShiftChangeService.ShiftRecord> list(UUID tenantId) {
        return delegate.list(tenantId);
    }

    private static int required(int updated, String operation) {
        if (updated != 1) {
            throw new ProcessRejectedException("P007 " + operation + " failed closed");
        }
        return updated;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
