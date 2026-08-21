package cn.shangjingu.platform.iam.authorization;

import java.util.Objects;
import java.util.UUID;

public record AuthorizationTarget(
        UUID tenantId,
        UUID employeeId,
        UUID orgId,
        UUID positionId,
        UUID ownerEmployeeId) {
    public AuthorizationTarget {
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
