package cn.shangjingu.platform.iam.mfa;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.iam.mfa.MfaRejectedException.Reason;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TotpCredentialService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final long STEP_SECONDS = 30L;
    private static final String ENROLL_RESOURCE = "iam.mfa_credential";
    private static final String CONFIRM_RESOURCE = "iam.mfa_credential.confirm";
    private static final String DISABLE_RESOURCE = "iam.mfa_credential.disable";

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final Repository repository;
    private final MfaSecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public TotpCredentialService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            Repository repository,
            MfaSecretCipher cipher) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.repository = repository;
        this.cipher = cipher;
    }

    public MfaStatus status(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository
                .findByUser(actor.tenantId(), actor.userId())
                .map(c -> new MfaStatus(true, c.status(), c.versionNo(), c.confirmedAt(), c.disabledAt()))
                .orElseGet(() -> new MfaStatus(false, "NONE", 0, null, null)));
    }

    public Enrollment enroll(
            DatabaseSecurityContext actor,
            String idempotencyKey,
            String requestHash,
            String issuer,
            String accountName) {
        requireMutationActor(actor);
        if (blank(issuer) || blank(accountName)) {
            throw rejected(Reason.INVALID_REQUEST, "issuer and accountName are required");
        }
        return transactions.required(actor, () -> {
            UUID employeeActorId = actor.employeeId();
            Optional<Credential> current = repository.findByUser(actor.tenantId(), actor.userId());
            UUID proposedId = current.filter(c -> !"ACTIVE".equals(c.status()))
                    .map(Credential::id)
                    .orElseGet(UUID::randomUUID);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    employeeActorId,
                    idempotencyKey,
                    requestHash,
                    ENROLL_RESOURCE,
                    proposedId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                Credential existing = repository
                        .findById(actor.tenantId(), claim.resourceId())
                        .orElseThrow(() -> rejected(Reason.NOT_FOUND, "idempotent MFA credential no longer exists"));
                if ("DISABLED".equals(existing.status())) {
                    throw rejected(Reason.CONFLICT, "MFA credential is disabled");
                }
                return enrollment(existing, issuer, accountName);
            }
            if (current.isPresent() && "ACTIVE".equals(current.get().status())) {
                throw rejected(Reason.CONFLICT, "MFA credential already exists");
            }
            byte[] secret = new byte[20];
            random.nextBytes(secret);
            byte[] secretCipher = cipher.encrypt(secret);
            if (current.isPresent()) {
                Credential restartable = current.get();
                if (repository.reset(
                                actor.tenantId(),
                                restartable.id(),
                                restartable.versionNo(),
                                secretCipher,
                                employeeActorId)
                        != 1) {
                    throw rejected(Reason.CONFLICT, "MFA concurrent update conflict");
                }
                Credential reset = repository
                        .findById(actor.tenantId(), restartable.id())
                        .orElseThrow(() -> rejected(Reason.NOT_FOUND, "MFA credential reset failed"));
                return enrollment(reset, issuer, accountName);
            }
            Credential created = new Credential(
                    claim.resourceId(),
                    actor.tenantId(),
                    actor.userId(),
                    "TOTP",
                    secretCipher,
                    "PENDING",
                    0,
                    null,
                    null);
            repository.insert(created, employeeActorId);
            return enrollment(created, issuer, accountName);
        });
    }

    public Credential confirm(DatabaseSecurityContext actor, int expectedVersion, String code) {
        requireMutationActor(actor);
        return transactions.required(actor, () -> confirmCurrent(actor, expectedVersion, code));
    }

    public Credential confirm(
            DatabaseSecurityContext actor,
            String idempotencyKey,
            String requestHash,
            int expectedVersion,
            String code) {
        requireMutationActor(actor);
        return transactions.required(actor, () -> {
            Credential current = repository
                    .findByUser(actor.tenantId(), actor.userId())
                    .orElseThrow(() -> rejected(Reason.NOT_FOUND, "MFA enrollment not found"));
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    CONFIRM_RESOURCE,
                    current.id(),
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                Credential existing = repository
                        .findById(actor.tenantId(), claim.resourceId())
                        .orElseThrow(() -> rejected(Reason.NOT_FOUND, "idempotent MFA credential no longer exists"));
                if (!"ACTIVE".equals(existing.status())) {
                    throw rejected(Reason.CONFLICT, "idempotent MFA confirmation has no active result");
                }
                return existing;
            }
            return confirmCurrent(actor, current, expectedVersion, code);
        });
    }

    private Credential confirmCurrent(DatabaseSecurityContext actor, int expectedVersion, String code) {
        Credential current = repository
                .findByUser(actor.tenantId(), actor.userId())
                .orElseThrow(() -> rejected(Reason.NOT_FOUND, "MFA enrollment not found"));
        return confirmCurrent(actor, current, expectedVersion, code);
    }

    private Credential confirmCurrent(
            DatabaseSecurityContext actor, Credential current, int expectedVersion, String code) {
        if (!"PENDING".equals(current.status())) {
            throw rejected(Reason.CONFLICT, "MFA enrollment is not pending");
        }
        if (current.versionNo() != expectedVersion) {
            throw rejected(Reason.CONFLICT, "MFA version conflict");
        }
        if (!verify(cipher.decrypt(current.secretCipher()), code, Instant.now())) {
            throw rejected(Reason.ASSERTION_REJECTED, "invalid MFA assertion");
        }
        UUID employeeActorId = actor.employeeId();
        if (repository.activate(actor.tenantId(), current.id(), expectedVersion, employeeActorId) != 1) {
            throw rejected(Reason.CONFLICT, "MFA concurrent update conflict");
        }
        repository.setAccountMfaLevel(actor.tenantId(), actor.userId(), (short) 1, employeeActorId);
        return repository
                .findById(actor.tenantId(), current.id())
                .orElseThrow(() -> rejected(Reason.NOT_FOUND, "MFA credential not found after activation"));
    }

    public void disable(DatabaseSecurityContext actor, int expectedVersion, String code) {
        requireMutationActor(actor);
        transactions.required(actor, () -> {
            disableCurrent(actor, expectedVersion, code);
            return null;
        });
    }

    public void disable(
            DatabaseSecurityContext actor,
            String idempotencyKey,
            String requestHash,
            int expectedVersion,
            String code) {
        requireMutationActor(actor);
        transactions.required(actor, () -> {
            Credential current = repository
                    .findByUser(actor.tenantId(), actor.userId())
                    .orElseThrow(() -> rejected(Reason.NOT_FOUND, "active MFA credential not found"));
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    DISABLE_RESOURCE,
                    current.id(),
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                Credential existing = repository
                        .findById(actor.tenantId(), claim.resourceId())
                        .orElseThrow(() -> rejected(Reason.NOT_FOUND, "idempotent MFA credential no longer exists"));
                if (!"DISABLED".equals(existing.status())) {
                    throw rejected(Reason.CONFLICT, "idempotent MFA disable has no disabled result");
                }
                return null;
            }
            disableCurrent(actor, current, expectedVersion, code);
            return null;
        });
    }

    private void disableCurrent(DatabaseSecurityContext actor, int expectedVersion, String code) {
        Credential current = repository
                .findByUser(actor.tenantId(), actor.userId())
                .orElseThrow(() -> rejected(Reason.NOT_FOUND, "active MFA credential not found"));
        disableCurrent(actor, current, expectedVersion, code);
    }

    private void disableCurrent(DatabaseSecurityContext actor, Credential current, int expectedVersion, String code) {
        if (!"ACTIVE".equals(current.status())) {
            throw rejected(Reason.CONFLICT, "MFA credential is not active");
        }
        if (current.versionNo() != expectedVersion) {
            throw rejected(Reason.CONFLICT, "MFA version conflict");
        }
        if (!verify(cipher.decrypt(current.secretCipher()), code, Instant.now())) {
            throw rejected(Reason.ASSERTION_REJECTED, "invalid MFA assertion");
        }
        UUID employeeActorId = actor.employeeId();
        if (repository.disable(actor.tenantId(), current.id(), expectedVersion, employeeActorId) != 1) {
            throw rejected(Reason.CONFLICT, "MFA concurrent update conflict");
        }
        repository.setAccountMfaLevel(actor.tenantId(), actor.userId(), (short) 0, employeeActorId);
    }

    public boolean verifyLogin(UUID tenantId, UUID userId, short enrolledMfaLevel, String assertion) {
        if (enrolledMfaLevel <= 0) {
            return true;
        }
        if (tenantId == null || userId == null || blank(assertion)) {
            return false;
        }
        DatabaseSecurityContext actor = new DatabaseSecurityContext(tenantId, userId, null, null, null, null, null);
        return transactions.required(actor, () -> repository
                .findByUser(tenantId, userId)
                .filter(c -> "ACTIVE".equals(c.status()))
                .map(c -> verify(cipher.decrypt(c.secretCipher()), assertion, Instant.now()))
                .orElse(false));
    }

    public boolean verifyAssertion(UUID tenantId, UUID userId, String assertion) {
        return verifyLogin(tenantId, userId, (short) 1, assertion);
    }

    private Enrollment enrollment(Credential credential, String issuer, String accountName) {
        String secret = base32(cipher.decrypt(credential.secretCipher()));
        String uri = "otpauth://totp/" + url(issuer) + ":" + url(accountName) + "?secret=" + secret + "&issuer="
                + url(issuer) + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(credential.id(), credential.versionNo(), secret, uri, credential.status());
    }

    static boolean verify(byte[] secret, String code, Instant instant) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long counter = instant.getEpochSecond() / STEP_SECONDS;
        for (long drift = -1; drift <= 1; drift++) {
            if (code.equals(code(secret, counter + drift))) {
                return true;
            }
        }
        return false;
    }

    static String code(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new MfaRejectedException(Reason.UNAVAILABLE, "TOTP verification failed", ex);
        }
    }

    static String base32(byte[] bytes) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static MfaRejectedException rejected(Reason reason, String message) {
        return new MfaRejectedException(reason, message);
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null) {
            throw rejected(Reason.INVALID_REQUEST, "authenticated user is required");
        }
    }

    private static void requireMutationActor(DatabaseSecurityContext actor) {
        requireActor(actor);
        if (actor.employeeId() == null) {
            throw rejected(Reason.INVALID_REQUEST, "employee actor context is required");
        }
    }

    public interface Repository {
        Optional<Credential> findById(UUID tenantId, UUID id);

        Optional<Credential> findByUser(UUID tenantId, UUID userId);

        void insert(Credential credential, UUID actorId);

        int reset(UUID tenantId, UUID id, int expectedVersion, byte[] secretCipher, UUID actorId);

        int activate(UUID tenantId, UUID id, int expectedVersion, UUID actorId);

        int disable(UUID tenantId, UUID id, int expectedVersion, UUID actorId);

        void setAccountMfaLevel(UUID tenantId, UUID userId, short level, UUID actorId);
    }

    public record Credential(
            UUID id,
            UUID tenantId,
            UUID userId,
            String method,
            byte[] secretCipher,
            String status,
            int versionNo,
            Instant confirmedAt,
            Instant disabledAt) {}

    public record Enrollment(UUID credentialId, int versionNo, String secret, String otpauthUri, String status) {}

    public record MfaStatus(
            boolean configured, String status, int versionNo, Instant confirmedAt, Instant disabledAt) {}
}
