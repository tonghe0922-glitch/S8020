package cn.shangjingu.platform.iam.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IdentityRecord(
        UUID id,
        UUID tenantId,
        UUID userId,
        UUID employeeId,
        String identityType,
        String identityName,
        UUID orgId,
        UUID positionId,
        boolean primary,
        OffsetDateTime effectiveStartAt,
        OffsetDateTime effectiveEndAt) {}
