package cn.shangjingu.platform.iam.session;

import java.time.Instant;
import java.util.UUID;

public record SessionContext(
        UUID tenantId,
        UUID userId,
        UUID identityId,
        UUID employeeId,
        UUID appointmentId,
        UUID orgId,
        UUID positionId,
        Instant issuedAt) {
}
