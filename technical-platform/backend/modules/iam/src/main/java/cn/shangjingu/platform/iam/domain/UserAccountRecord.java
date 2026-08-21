package cn.shangjingu.platform.iam.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserAccountRecord(
        UUID id,
        UUID tenantId,
        String loginName,
        String passwordHash,
        String status,
        OffsetDateTime lastLoginAt,
        short mfaLevel) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
