package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.SessionTokens;
import java.time.Instant;
import java.util.UUID;

public record SessionTokenResponse(
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt,
        UUID tenantId,
        UUID userId,
        UUID identityId,
        UUID employeeId,
        UUID appointmentId,
        UUID orgId,
        UUID positionId) {
    public static SessionTokenResponse from(SessionTokens tokens) {
        return new SessionTokenResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessExpiresAt(),
                tokens.refreshExpiresAt(),
                tokens.context().tenantId(),
                tokens.context().userId(),
                tokens.context().identityId(),
                tokens.context().employeeId(),
                tokens.context().appointmentId(),
                tokens.context().orgId(),
                tokens.context().positionId());
    }
}
