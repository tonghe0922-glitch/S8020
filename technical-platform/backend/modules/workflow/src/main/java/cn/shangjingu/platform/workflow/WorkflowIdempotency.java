package cn.shangjingu.platform.workflow;

import java.util.UUID;

public interface WorkflowIdempotency {
    Claim claim(UUID tenantId, UUID actorId, String key, String requestHash, String resourceType, UUID proposedResourceId);

    record Claim(UUID resourceId, boolean existing) {}
}
