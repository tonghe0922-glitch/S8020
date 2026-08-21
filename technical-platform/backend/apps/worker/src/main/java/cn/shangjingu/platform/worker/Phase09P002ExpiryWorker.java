package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.audit.PlatformAuditWriter;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import cn.shangjingu.platform.workflow.WorkflowSystemActionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reconciles expired P002 grants without impersonating an employee.
 *
 * <p>Authorization already stops at iam.user_role.effective_end_at. This worker makes the business,
 * workflow, audit, outbox and DLQ facts catch up to that security truth.</p>
 */
public final class Phase09P002ExpiryWorker {
    private static final String S07 = "S07";
    private static final String S08 = "S08";
    private static final String S07_LABEL = "定期复核";
    private static final String S08_LABEL = "到期/调岗/离职回收";
    private static final String EVENT_TYPE = "P002_PERMISSION_REQUEST_EVENT";
    private static final String AGGREGATE_TYPE = "P002_PERMISSION_REQUEST";

    private final TenantTransactionRunner transactions;
    private final JdbcTemplate jdbc;
    private final WorkflowSystemActionService systemActions;
    private final TransactionalOutboxService outbox;
    private final PlatformAuditWriter audit;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public Phase09P002ExpiryWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            WorkflowSystemActionService systemActions,
            TransactionalOutboxService outbox,
            PlatformAuditWriter audit,
            ObjectMapper mapper,
            int maxAttempts,
            Duration baseBackoff,
            Duration maxBackoff) {
        if (transactions == null || jdbc == null || systemActions == null || outbox == null || audit == null || mapper == null) {
            throw new IllegalArgumentException("P002 expiry dependencies are required");
        }
        if (maxAttempts <= 0 || baseBackoff == null || maxBackoff == null
                || baseBackoff.isZero() || baseBackoff.isNegative() || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("P002 expiry retry policy is invalid");
        }
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.systemActions = systemActions;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoff.toMillis();
        this.maxBackoffMs = maxBackoff.toMillis();
    }

    public int runOnce(int maxItems) {
        if (maxItems <= 0) return 0;
        OffsetDateTime cutoff = databaseNow();
        List<UUID> tenants = activeTenants();
        int processed = 0;
        boolean progress;
        do {
            progress = false;
            for (UUID tenantId : tenants) {
                if (processed >= maxItems) return processed;
                if (processTenantOnce(tenantId, cutoff)) {
                    processed++;
                    progress = true;
                }
            }
        } while (progress && processed < maxItems);
        return processed;
    }

    boolean processTenantOnce(UUID tenantId, OffsetDateTime cutoff) {
        AtomicReference<DueGrant> attempted = new AtomicReference<>();
        try {
            Boolean handled = transactions.required(tenantId, () -> {
                DueGrant due = lockNextDue(tenantId, cutoff);
                if (due == null) return false;
                attempted.set(due);
                reconcile(due);
                return true;
            });
            return Boolean.TRUE.equals(handled);
        } catch (RuntimeException failure) {
            DueGrant due = attempted.get();
            if (due == null) throw failure;
            recordFailure(due, failure);
            return true;
        }
    }

    private void reconcile(DueGrant due) {
        if (due.workflowInstanceId() == null || due.userRoleId() == null) {
            throw new IllegalStateException("P002 expired grant is missing workflow or user-role linkage");
        }
        if (!S07_LABEL.equals(due.requestStatus())) {
            throw new IllegalStateException("P002 expired grant is not in the authoritative S07 business state");
        }
        WorkflowRuntimeService.Result moved = systemActions.act(new WorkflowSystemActionService.SystemActionCommand(
                due.tenantId(), due.workflowInstanceId(), S07, "AUTO_EXPIRE",
                "权限有效期届满，系统执行自动回收", "p002-expiry:" + due.requestId() + ":workflow"));
        if (!S08.equals(moved.instance().currentNodeCode())) {
            throw new IllegalStateException("P002 AUTO_EXPIRE did not reach source-backed S08");
        }

        int roleChanged = jdbc.update("""
                update iam.user_role
                   set effective_end_at=case when effective_end_at is null or effective_end_at>? then ? else effective_end_at end,
                       updated_by=null,updated_at=now()
                 where tenant_id=? and id=? and not is_deleted
                """, due.effectiveEndAt(), due.effectiveEndAt(), due.tenantId(), due.userRoleId());
        if (roleChanged != 1) throw new IllegalStateException("P002 expired user-role linkage changed concurrently");

        // permission_request_grant was introduced by V107 without soft-delete semantics.
        // Eligibility is represented by grant_status plus retry/DLQ facts, so no is_deleted predicate is valid here.
        int grantChanged = jdbc.update("""
                update iam.permission_request_grant
                   set grant_status='REVOKED',revoked_by=null,revoked_at=?,revoke_reason='AUTO_EXPIRE',
                       revoke_source='AUTO_EXPIRE',expiry_retry_count=0,expiry_next_attempt_at=null,
                       expiry_last_error=null,updated_by=null,updated_at=now()
                 where tenant_id=? and id=? and grant_status='ACTIVE'
                """, due.effectiveEndAt(), due.tenantId(), due.grantId());
        if (grantChanged != 1) throw new IllegalStateException("P002 expired grant changed concurrently");

        int requestChanged = jdbc.update("""
                update iam.permission_request
                   set status=?,result_summary='权限已按有效期自动失效并进入回收节点',actual_end_at=coalesce(actual_end_at,?),
                       version_no=version_no+1,updated_by=null,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and status=? and workflow_instance_id=? and not is_deleted
                """, S08_LABEL, due.effectiveEndAt(), due.tenantId(), due.requestId(), due.requestVersion(),
                S07_LABEL, due.workflowInstanceId());
        if (requestChanged != 1) throw new IllegalStateException("P002 expired request changed concurrently");

        audit.appendCriticalOperation(new PlatformAuditWriter.OperationCommand(
                due.tenantId(), null, null, "P002_AUTO_EXPIRE", AGGREGATE_TYPE, due.requestId(),
                "p002-expiry:" + due.requestId()));
        outbox.enqueue(new TransactionalOutboxService.Command(
                due.tenantId(), null, AGGREGATE_TYPE, due.requestId(), EVENT_TYPE, 1,
                eventPayload(due), "p002:" + due.requestId() + ":auto_expired:S08"));
    }

    private DueGrant lockNextDue(UUID tenantId, OffsetDateTime cutoff) {
        return jdbc.query("""
                select g.id grant_id,g.permission_request_id,g.user_role_id,g.effective_end_at,g.expiry_retry_count,
                       p.workflow_instance_id,p.business_no,p.version_no,p.status,p.owner_employee_id
                  from iam.permission_request_grant g
                  join iam.permission_request p
                    on p.tenant_id=g.tenant_id and p.id=g.permission_request_id and not p.is_deleted
                 where g.tenant_id=? and g.grant_status='ACTIVE'
                   and g.effective_end_at <= ?
                   and g.expiry_dead_lettered_at is null
                   and (g.expiry_next_attempt_at is null or g.expiry_next_attempt_at <= ?)
                 order by g.effective_end_at,g.created_at,g.id
                 for update of g,p skip locked
                 limit 1
                """, rs -> rs.next() ? new DueGrant(
                tenantId,
                rs.getObject("grant_id", UUID.class),
                rs.getObject("permission_request_id", UUID.class),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("business_no"),
                rs.getInt("version_no"),
                rs.getString("status"),
                rs.getObject("user_role_id", UUID.class),
                rs.getObject("effective_end_at", OffsetDateTime.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getInt("expiry_retry_count")) : null,
                tenantId, cutoff, cutoff);
    }

    private void recordFailure(DueGrant due, RuntimeException failure) {
        transactions.required(due.tenantId(), () -> {
            RetryState state = jdbc.query("""
                    select grant_status,expiry_retry_count,expiry_dead_lettered_at
                      from iam.permission_request_grant
                     where tenant_id=? and id=?
                     for update
                    """, rs -> rs.next() ? new RetryState(
                    rs.getString("grant_status"), rs.getInt("expiry_retry_count"),
                    rs.getObject("expiry_dead_lettered_at", OffsetDateTime.class)) : null,
                    due.tenantId(), due.grantId());
            if (state == null || !"ACTIVE".equals(state.grantStatus()) || state.deadLetteredAt() != null) return null;
            int next = state.retryCount() + 1;
            String message = sanitize(failure.getMessage());
            if (next >= maxAttempts) {
                int changed = jdbc.update("""
                        update iam.permission_request_grant
                           set expiry_retry_count=?,expiry_next_attempt_at=null,expiry_last_error=?,
                               expiry_dead_lettered_at=now(),updated_by=null,updated_at=now()
                         where tenant_id=? and id=? and grant_status='ACTIVE' and expiry_dead_lettered_at is null
                        """, next, message, due.tenantId(), due.grantId());
                if (changed != 1) throw new IllegalStateException("P002 expiry DLQ state changed concurrently");
                jdbc.update("""
                        insert into integration.dead_letter(id,tenant_id,source_type,source_id,payload,error_code,error_message,status)
                        select ?,?,'P002_AUTO_EXPIRE',?,cast(? as jsonb),?,?,'OPEN'
                        where not exists (
                            select 1 from integration.dead_letter d
                             where d.tenant_id=? and d.source_type='P002_AUTO_EXPIRE'
                               and d.source_id=? and not d.is_deleted)
                        """, UUID.randomUUID(), due.tenantId(), due.grantId().toString(), deadLetterPayload(due),
                        failure.getClass().getSimpleName(), message,
                        due.tenantId(), due.grantId().toString());
                audit.appendCriticalOperation(new PlatformAuditWriter.OperationCommand(
                        due.tenantId(), null, null, "P002_AUTO_EXPIRE_DEAD_LETTER", AGGREGATE_TYPE,
                        due.requestId(), "p002-expiry-dlq:" + due.grantId()));
            } else {
                long delay = retryDelayMillis(next, baseBackoffMs, maxBackoffMs);
                int changed = jdbc.update("""
                        update iam.permission_request_grant
                           set expiry_retry_count=?,expiry_next_attempt_at=now() + (? * interval '1 millisecond'),
                               expiry_last_error=?,updated_by=null,updated_at=now()
                         where tenant_id=? and id=? and grant_status='ACTIVE' and expiry_dead_lettered_at is null
                        """, next, delay, message, due.tenantId(), due.grantId());
                if (changed != 1) throw new IllegalStateException("P002 expiry retry state changed concurrently");
            }
            return null;
        });
    }

    private String eventPayload(DueGrant due) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("requestId", due.requestId().toString());
        payload.put("businessNo", due.businessNo());
        payload.put("event", "AUTO_EXPIRED");
        payload.put("nodeCode", S08);
        ArrayNode recipients = payload.putArray("recipientEmployeeIds");
        if (due.ownerEmployeeId() != null) recipients.add(due.ownerEmployeeId().toString());
        return json(payload);
    }

    private String deadLetterPayload(DueGrant due) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("grantId", due.grantId().toString());
        payload.put("requestId", due.requestId().toString());
        payload.put("workflowInstanceId", due.workflowInstanceId() == null ? "" : due.workflowInstanceId().toString());
        payload.put("effectiveEndAt", due.effectiveEndAt().toString());
        return json(payload);
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("P002 worker payload cannot be serialized", failure);
        }
    }

    private OffsetDateTime databaseNow() {
        return jdbc.query("select clock_timestamp()", rs -> {
            if (!rs.next()) throw new IllegalStateException("database clock query returned no row");
            return rs.getObject(1, OffsetDateTime.class);
        });
    }

    private List<UUID> activeTenants() {
        return jdbc.query("select id from core.tenant where status='ACTIVE' order by id",
                (rs, row) -> rs.getObject("id", UUID.class));
    }

    static long retryDelayMillis(int retryCount, long baseMs, long maxMs) {
        if (retryCount <= 0) return 0;
        double calculated = baseMs * Math.pow(2.0, Math.max(0, retryCount - 1));
        return Math.min(maxMs, Math.round(calculated));
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) return "P002 expiry reconciliation failed";
        String compact = message.replaceAll("[\\r\\n\\t]", " ");
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private record DueGrant(
            UUID tenantId, UUID grantId, UUID requestId, UUID workflowInstanceId, String businessNo,
            int requestVersion, String requestStatus, UUID userRoleId, OffsetDateTime effectiveEndAt,
            UUID ownerEmployeeId, int retryCount) {}
    private record RetryState(String grantStatus, int retryCount, OffsetDateTime deadLetteredAt) {}
}
