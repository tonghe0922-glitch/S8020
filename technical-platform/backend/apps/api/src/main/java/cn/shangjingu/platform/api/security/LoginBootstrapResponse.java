package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.SessionTokens;
import java.time.Instant;
import java.util.UUID;

public record LoginBootstrapResponse(
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
        UUID positionId,
        SessionViewResponse session) {

    static LoginBootstrapResponse from(SessionTokens tokens, SessionViewResponse session) {
        var context = tokens.context();
        return new LoginBootstrapResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessExpiresAt(),
                tokens.refreshExpiresAt(),
                context.tenantId(),
                context.userId(),
                context.identityId(),
                context.employeeId(),
                context.appointmentId(),
                context.orgId(),
                context.positionId(),
                session);
    }
}
