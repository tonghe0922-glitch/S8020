package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** Production fail-closed adapter for P008 quota-ledger and temporal invariants. */
@Primary
@Repository
public class GuardedLeaveRepository implements LeaveService.Repository {
    private static final Set<String> LEDGER_TYPES =
            Set.of("RESERVE", "DEDUCT", "RELEASE", "ADJUST");
    private final JdbcLeaveRepository delegate;
    private final P008LeaveStatusProjectionWriter statusWriter;

    public GuardedLeaveRepository(JdbcLeaveRepository delegate) {
        this(delegate, null);
    }

    @Autowired
    public GuardedLeaveRepository(
            JdbcLeaveRepository delegate, P008LeaveStatusProjectionWriter statusWriter) {
        this.delegate = delegate;
        this.statusWriter = statusWriter;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return delegate.workflowVersion(tenantId);
    }

    @Override
    public Optional<LeaveService.FormRef> form(UUID tenantId) {
        return delegate.form(tenantId);
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permission, UUID orgId) {
        return delegate.permissionCandidates(tenantId, permission, orgId);
    }

    @Override
    public boolean hasTimeConflict(UUID tenantId, UUID employeeId, Instant start, Instant end) {
        return delegate.hasTimeConflict(tenantId, employeeId, start, end);
    }

    @Override
    public void insert(LeaveService.LeaveRecord record, UUID actor) {
        delegate.insert(withCanonicalHandoverAgent(record), actor);
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
            UUID tenantId, UUID id, int version, String status, Instant closedAt, UUID actor) {
        int updated = statusWriter == null
                ? delegate.moveStatus(tenantId, id, version, status, closedAt, actor)
                : statusWriter.moveStatus(tenantId, id, version, status, closedAt, actor);
        return required(updated, "workflow projection transition");
    }

    @Override
    public int markQuotaReserved(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markQuotaReserved(tenantId, id, actor), "quota reservation fact");
    }

    @Override
    public int markHandover(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markHandover(tenantId, id, actor), "handover confirmation fact");
    }

    @Override
    public int markDecision(UUID tenantId, UUID id, String decision, UUID actor) {
        return required(
                delegate.markDecision(tenantId, id, decision, actor), "approval decision fact");
    }

    @Override
    public int markQuotaSettled(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markQuotaSettled(tenantId, id, actor), "quota settlement fact");
    }

    @Override
    public int markAttendance(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markAttendance(tenantId, id, actor), "attendance mark fact");
    }

    @Override
    public int markLeaveStarted(UUID tenantId, UUID id, Instant actualAt, UUID actor) {
        return required(
                delegate.markLeaveStarted(tenantId, id, actualAt, actor),
                "actual leave start fact");
    }

    @Override
    public int markReturned(UUID tenantId, UUID id, Instant actualAt, UUID actor) {
        LeaveService.LeaveRecord record = delegate.find(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("P008 leave request not found"));
        if (actualAt == null) {
            throw new ProcessRejectedException("P008 return actualAt is required");
        }
        if (record.leaveStartedAt() == null) {
            throw new ProcessRejectedException("P008 leave must start before return-to-work");
        }
        if (actualAt.isBefore(record.leaveStartedAt())) {
            throw new ProcessRejectedException(
                    "P008 return-to-work cannot precede actual leave start");
        }
        return required(
                delegate.markReturned(tenantId, id, actualAt, actor), "return-to-work fact");
    }

    @Override
    public int markQuotaAdjusted(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markQuotaAdjusted(tenantId, id, actor), "quota adjustment fact");
    }

    @Override
    public int markDayClosed(UUID tenantId, UUID id, UUID actor) {
        return required(delegate.markDayClosed(tenantId, id, actor), "day-close fact");
    }

    @Override
    public void appendLedger(
            UUID tenantId,
            UUID id,
            String entryType,
            BigDecimal amount,
            String note,
            UUID actor) {
        validateLedger(entryType, amount);
        delegate.appendLedger(tenantId, id, entryType, amount, note, actor);
    }

    @Override
    public Optional<LeaveService.LeaveRecord> find(UUID tenantId, UUID id) {
        return delegate.find(tenantId, id);
    }

    @Override
    public List<LeaveService.LeaveRecord> list(UUID tenantId) {
        return delegate.list(tenantId);
    }

    @Override
    public List<LeaveService.LedgerEntry> ledger(UUID tenantId) {
        return delegate.ledger(tenantId);
    }

    static String canonicalHandoverAgentId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return UUID.fromString(trimmed).toString().replace("-", "");
        } catch (IllegalArgumentException ignored) {
            if (trimmed.length() > 32) {
                throw new ProcessRejectedException(
                        "P008 handover agent reference exceeds the canonical varchar(32) contract");
            }
            return trimmed;
        }
    }

    private static LeaveService.LeaveRecord withCanonicalHandoverAgent(
            LeaveService.LeaveRecord record) {
        return new LeaveService.LeaveRecord(
                record.id(),
                record.tenantId(),
                record.businessNo(),
                record.workflowInstanceId(),
                record.workflowInstanceNo(),
                record.currentNodeCode(),
                record.status(),
                record.versionNo(),
                record.subject(),
                record.reason(),
                record.ownerCenterId(),
                record.ownerEmployeeId(),
                record.attendanceType(),
                record.startAt(),
                record.endAt(),
                record.durationHours(),
                record.quotaAccountId(),
                record.quotaAmount(),
                canonicalHandoverAgentId(record.handoverAgentId()),
                record.knownImpact(),
                record.quotaReservedAt(),
                record.handoverConfirmedAt(),
                record.decision(),
                record.approvedAt(),
                record.rejectedAt(),
                record.quotaSettledAt(),
                record.attendanceMarkedAt(),
                record.leaveStartedAt(),
                record.returnedAt(),
                record.quotaAdjustedAt(),
                record.dayClosedAt(),
                record.closedAt(),
                record.updatedAt());
    }

    private static void validateLedger(String entryType, BigDecimal amount) {
        if (!LEDGER_TYPES.contains(entryType)) {
            throw new ProcessRejectedException("P008 quota ledger entry type is invalid");
        }
        if (amount == null) {
            throw new ProcessRejectedException("P008 quota ledger amount is required");
        }
        if ("ADJUST".equals(entryType)) {
            if (amount.signum() == 0) {
                throw new ProcessRejectedException("P008 quota adjustment must be non-zero");
            }
        } else if (amount.signum() <= 0) {
            throw new ProcessRejectedException("P008 quota ledger amount must be positive");
        }
    }

    private static int required(int updated, String operation) {
        if (updated != 1) {
            throw new ProcessRejectedException("P008 " + operation + " failed closed");
        }
        return updated;
    }
}
