package cn.shangjingu.platform.core.database;

import java.util.Objects;
import java.util.UUID;

public record DatabaseSecurityContext(
        UUID tenantId, UUID userId, UUID identityId, UUID employeeId, UUID appointmentId, UUID orgId, UUID positionId) {
    public DatabaseSecurityContext {
        Objects.requireNonNull(tenantId, "tenantId");
    }

    public static DatabaseSecurityContext tenantOnly(UUID tenantId) {
        return new DatabaseSecurityContext(tenantId, null, null, null, null, null, null);
    }
}
