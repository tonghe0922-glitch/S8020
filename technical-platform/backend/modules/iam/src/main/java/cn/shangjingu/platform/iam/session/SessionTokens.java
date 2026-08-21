package cn.shangjingu.platform.iam.session;

import java.time.Instant;

public record SessionTokens(
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt,
        SessionContext context) {
}
