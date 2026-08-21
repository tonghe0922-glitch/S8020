package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.iam.application.PermissionRequestService;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Source-backed PHASE-09 key-node dual-review guard for P002. */
@Service
public final class P002ReviewSeparationPolicy {
    private static final Set<String> DISTINCT_REVIEW_STATES = Set.of("数据责任人复核", "高风险权限审批");
    private final TenantTransactionRunner transactions;
    private final JdbcTemplate jdbc;

    public P002ReviewSeparationPolicy(TenantTransactionRunner transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public void requireDistinctReviewer(
            DatabaseSecurityContext actor,
            PermissionRequestService.PermissionRequest request,
            String idempotencyKey) {
        if (actor == null || actor.employeeId() == null || request == null || request.workflowInstanceId() == null) {
            throw new ProcessRejectedException("P002 review separation context is incomplete");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ProcessRejectedException("P002 review idempotency key is required");
        }
        if (!DISTINCT_REVIEW_STATES.contains(request.status())) return;
        String workflowRequestId = idempotencyKey + ":workflow";
        Boolean rejected = transactions.required(actor, () -> {
            Long priorApprovals = jdbc.queryForObject("""
                    select count(*)
                      from workflow.wf_action_log
                     where tenant_id=? and instance_id=? and operator_id=? and not is_deleted
                       and action_code in ('APPROVE','APPROVE_STANDARD','APPROVE_HIGH')
                    """, Long.class, actor.tenantId(), request.workflowInstanceId(), actor.employeeId());
            if (priorApprovals == null || priorApprovals == 0) return false;

            // An exact HTTP idempotency replay is allowed to reach the service-level claim. The
            // workflow log stores PermissionRequestService's scoped key, so this does not weaken
            // separation for a fresh approval attempt with a different Idempotency-Key.
            if (workflowRequestId.length() <= 128) {
                Long exactReplay = jdbc.queryForObject("""
                        select count(*)
                          from workflow.wf_action_log
                         where tenant_id=? and instance_id=? and operator_id=? and request_id=? and not is_deleted
                           and action_code in ('APPROVE','APPROVE_STANDARD','APPROVE_HIGH')
                        """, Long.class, actor.tenantId(), request.workflowInstanceId(), actor.employeeId(), workflowRequestId);
                if (exactReplay != null && exactReplay > 0) return false;
            }
            return true;
        });
        if (Boolean.TRUE.equals(rejected)) {
            throw new ProcessRejectedException(
                    "P002 key review nodes require a different reviewer from prior approval actions");
        }
    }
}
