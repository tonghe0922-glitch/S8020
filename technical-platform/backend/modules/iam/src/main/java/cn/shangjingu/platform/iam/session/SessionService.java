package cn.shangjingu.platform.iam.session;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.IdentityRecord;
import cn.shangjingu.platform.org.domain.AppointmentRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SessionService {
    private static final int TOKEN_BYTES = 32;

    private final IdentityDirectoryService identities;
    private final SessionStore store;
    private final SessionPolicy policy;
    private final Clock clock;
    private final SecureRandom random;

    public SessionService(IdentityDirectoryService identities, SessionStore store, SessionPolicy policy) {
        this(identities, store, policy, Clock.systemUTC(), new SecureRandom());
    }

    SessionService(
            IdentityDirectoryService identities,
            SessionStore store,
            SessionPolicy policy,
            Clock clock,
            SecureRandom random) {
        this.identities = Objects.requireNonNull(identities);
        this.store = Objects.requireNonNull(store);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public SessionTokens issue(UUID tenantId, UUID userId, UUID identityId) {
        return issueResolved(resolveIdentity(tenantId, userId, identityId, null));
    }

    public SessionTokens issue(IdentityRecord identity, AppointmentRecord appointment) {
        return issueResolved(
                new ResolvedIdentity(Objects.requireNonNull(identity), Objects.requireNonNull(appointment)));
    }

    public Optional<SessionContext> authenticateAccess(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Optional<SessionStore.StoredSession> found = store.findByAccessDigest(digest(token));
        if (found.isEmpty()) return Optional.empty();
        SessionStore.StoredSession session = found.orElseThrow();
        if (!authoritativeContextStillActive(session.context())) {
            store.revoke(session);
            return Optional.empty();
        }
        return Optional.of(session.context());
    }

    public SessionTokens refresh(String token) {
        if (token == null || token.isBlank()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_REFRESH);
        }

        String refreshDigest = digest(token);
        Optional<SessionStore.StoredSession> found = store.findByRefreshDigest(refreshDigest);
        if (found.isEmpty()) {
            if (store.wasRefreshUsed(refreshDigest)) {
                throw new SessionRejectedException(SessionRejectedException.Reason.REFRESH_REPLAY);
            }
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_REFRESH);
        }

        SessionStore.StoredSession current = found.orElseThrow();
        ResolvedIdentity resolved;
        try {
            resolved = resolveIdentity(
                    current.context().tenantId(),
                    current.context().userId(),
                    current.context().identityId(),
                    current.context().appointmentId());
        } catch (SessionRejectedException exception) {
            store.revoke(current);
            throw exception;
        }

        RawPair raw = rawPair();
        SessionStore.StoredSession replacement =
                stored(current.familyId(), resolved, raw.accessDigest(), raw.refreshDigest());
        SessionStore.RotationOutcome outcome =
                store.rotate(current, refreshDigest, replacement, policy.accessTtl(), policy.refreshTtl());

        if (outcome == SessionStore.RotationOutcome.REPLAYED) {
            throw new SessionRejectedException(SessionRejectedException.Reason.REFRESH_REPLAY);
        }
        if (outcome != SessionStore.RotationOutcome.ROTATED) {
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_REFRESH);
        }
        return tokens(raw, replacement);
    }

    public SessionTokens switchIdentity(String accessToken, UUID targetIdentityId) {
        SessionStore.StoredSession current = requireAccess(accessToken);
        ResolvedIdentity target =
                resolveIdentity(current.context().tenantId(), current.context().userId(), targetIdentityId, null);
        store.revoke(current);
        return issueResolved(target);
    }

    public boolean logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return false;
        Optional<SessionStore.StoredSession> found = store.findByAccessDigest(digest(accessToken));
        if (found.isEmpty()) return false;
        store.revoke(found.orElseThrow());
        return true;
    }

    public List<SessionSummary> listActive(UUID tenantId, UUID userId) {
        return store.listByUser(tenantId, userId).stream()
                .filter(session -> authoritativeContextStillActive(session.context()))
                .map(session -> new SessionSummary(
                        session.familyId(),
                        session.context().identityId(),
                        session.context().employeeId(),
                        session.context().orgId(),
                        session.context().positionId(),
                        session.context().issuedAt(),
                        session.accessExpiresAt(),
                        session.refreshExpiresAt()))
                .toList();
    }

    private SessionStore.StoredSession requireAccess(String token) {
        if (token == null || token.isBlank()) {
            throw new SessionRejectedException(SessionRejectedException.Reason.INVALID_ACCESS);
        }
        SessionStore.StoredSession session = store.findByAccessDigest(digest(token))
                .orElseThrow(() -> new SessionRejectedException(SessionRejectedException.Reason.INVALID_ACCESS));
        if (!authoritativeContextStillActive(session.context())) {
            store.revoke(session);
            throw new SessionRejectedException(SessionRejectedException.Reason.APPOINTMENT_INACTIVE);
        }
        return session;
    }

    private boolean authoritativeContextStillActive(SessionContext context) {
        try {
            ResolvedIdentity resolved = resolveIdentity(
                    context.tenantId(), context.userId(), context.identityId(), context.appointmentId());
            return resolved.identity().employeeId().equals(context.employeeId())
                    && resolved.identity().orgId().equals(context.orgId())
                    && resolved.identity().positionId().equals(context.positionId());
        } catch (SessionRejectedException exception) {
            return false;
        }
    }

    private SessionTokens issueResolved(ResolvedIdentity resolved) {
        RawPair raw = rawPair();
        SessionStore.StoredSession session =
                stored(UUID.randomUUID(), resolved, raw.accessDigest(), raw.refreshDigest());
        store.create(session, policy.accessTtl(), policy.refreshTtl());
        return tokens(raw, session);
    }

    private SessionStore.StoredSession stored(
            UUID familyId, ResolvedIdentity resolved, String accessDigest, String refreshDigest) {
        Instant now = clock.instant();
        SessionContext context = new SessionContext(
                resolved.identity().tenantId(),
                resolved.identity().userId(),
                resolved.identity().id(),
                resolved.identity().employeeId(),
                resolved.appointment().id(),
                resolved.identity().orgId(),
                resolved.identity().positionId(),
                now);
        return new SessionStore.StoredSession(
                familyId,
                context,
                accessDigest,
                refreshDigest,
                now.plus(policy.accessTtl()),
                now.plus(policy.refreshTtl()));
    }

    private SessionTokens tokens(RawPair raw, SessionStore.StoredSession session) {
        return new SessionTokens(
                raw.accessToken(),
                raw.refreshToken(),
                session.accessExpiresAt(),
                session.refreshExpiresAt(),
                session.context());
    }

    private ResolvedIdentity resolveIdentity(UUID tenantId, UUID userId, UUID identityId, UUID requiredAppointmentId) {
        IdentityRecord identity = identities
                .activeIdentity(tenantId, userId, identityId)
                .orElseThrow(() -> new SessionRejectedException(SessionRejectedException.Reason.IDENTITY_INACTIVE));
        AppointmentRecord appointment = requiredAppointmentId == null
                ? identities
                        .activeAppointment(tenantId, userId, identityId)
                        .orElseThrow(() ->
                                new SessionRejectedException(SessionRejectedException.Reason.APPOINTMENT_INACTIVE))
                : identities
                        .activeAppointment(tenantId, userId, identityId, requiredAppointmentId)
                        .orElseThrow(() ->
                                new SessionRejectedException(SessionRejectedException.Reason.APPOINTMENT_INACTIVE));
        return new ResolvedIdentity(identity, appointment);
    }

    private RawPair rawPair() {
        String accessToken = rawToken();
        String refreshToken = rawToken();
        return new RawPair(accessToken, refreshToken, digest(accessToken), digest(refreshToken));
    }

    private String rawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record SessionSummary(
            UUID familyId,
            UUID identityId,
            UUID employeeId,
            UUID orgId,
            UUID positionId,
            Instant issuedAt,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {}

    private record RawPair(String accessToken, String refreshToken, String accessDigest, String refreshDigest) {}

    private record ResolvedIdentity(IdentityRecord identity, AppointmentRecord appointment) {}
}
