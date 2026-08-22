package cn.shangjingu.platform.iam.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionStore {
    enum RotationOutcome {
        ROTATED,
        MISSING,
        REPLAYED
    }

    record StoredSession(
            UUID familyId,
            SessionContext context,
            String accessDigest,
            String refreshDigest,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {}

    void create(StoredSession session, Duration accessTtl, Duration refreshTtl);

    Optional<StoredSession> findByAccessDigest(String accessDigest);

    Optional<StoredSession> findByRefreshDigest(String refreshDigest);

    boolean wasRefreshUsed(String refreshDigest);

    RotationOutcome rotate(
            StoredSession current,
            String presentedRefreshDigest,
            StoredSession replacement,
            Duration accessTtl,
            Duration refreshTtl);

    void revoke(StoredSession session);

    default List<StoredSession> listByUser(UUID tenantId, UUID userId) {
        return List.of();
    }
}
