package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CoreWorkflowIdempotency implements WorkflowIdempotency {
    private static final Duration RETENTION = Duration.ofDays(7);
    private final IdempotencyRegistry registry;

    public CoreWorkflowIdempotency(IdempotencyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Claim claim(
            UUID tenantId, UUID actorId, String key, String requestHash, String resourceType, UUID proposedResourceId) {
        try {
            IdempotencyClaim claim =
                    registry.claim(tenantId, actorId, key, requestHash, resourceType, proposedResourceId, RETENTION);
            return new Claim(claim.resourceId(), claim.existing());
        } catch (ProcessRejectedException ex) {
            throw new WorkflowException(WorkflowException.Code.CONFLICT, ex.getMessage(), ex);
        }
    }
}
