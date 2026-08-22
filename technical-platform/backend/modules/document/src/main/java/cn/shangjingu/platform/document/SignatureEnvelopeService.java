package cn.shangjingu.platform.document;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.core.process.SequentialStateMachine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SignatureEnvelopeService {
    public static final String PROCESS_CODE = "P017";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(7);
    private static final SequentialStateMachine STATES =
            new SequentialStateMachine(List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final Repository repository;
    private final FileEvidenceCapability files;
    private final List<SignatureProviderCapability> providers;

    public SignatureEnvelopeService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            Repository repository,
            FileEvidenceCapability files,
            List<SignatureProviderCapability> providers) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.repository = repository;
        this.files = files;
        this.providers = List.copyOf(providers);
    }

    public Envelope create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        validateCreate(command);
        return transactions.required(actor, () -> {
            files.assertSafe(actor.tenantId(), command.sourceFileId(), command.documentHash());
            UUID proposed = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.userId(),
                    idempotencyKey,
                    requestHash,
                    "document.signature_envelope",
                    proposed,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return repository
                        .find(actor.tenantId(), claim.resourceId())
                        .orElseThrow(
                                () -> new ProcessRejectedException("idempotent signature envelope no longer exists"));
            }
            String no = numbers.next(actor.tenantId(), actor.userId(), PROCESS_CODE);
            Envelope envelope = new Envelope(
                    proposed,
                    actor.tenantId(),
                    no,
                    no,
                    STATES.initial(),
                    0,
                    command.documentHash(),
                    command.templateVersion(),
                    command.signingOrder(),
                    command.signDeadlineAt(),
                    "PENDING",
                    null,
                    command.authenticationMethod(),
                    command.businessDate(),
                    command.documentType(),
                    command.resultSummary(),
                    Instant.now(),
                    null,
                    actor.userId());
            repository.insert(envelope, command.sourceFileId(), command.parties(), actor.userId());
            return envelope;
        });
    }

    public Optional<Envelope> find(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public Envelope advance(DatabaseSecurityContext actor, UUID id, int expectedVersion, String requestedStatus) {
        return transactions.required(actor, () -> {
            Envelope current = required(actor.tenantId(), id);
            if (current.versionNo() != expectedVersion) {
                throw new ProcessRejectedException("signature envelope version conflict");
            }
            if (Objects.equals(current.status(), requestedStatus)) {
                return current;
            }
            STATES.requireTransition(current.status(), requestedStatus);
            if ("S07".equals(requestedStatus)) {
                throw new ProcessRejectedException("S07 requires verified provider callback evidence");
            }
            if ("S04".equals(requestedStatus)) {
                provider().initiate(current, repository.parties(actor.tenantId(), id));
            }
            if ("S08".equals(requestedStatus)
                    || "S09".equals(requestedStatus)
                    || SequentialStateMachine.CLOSED.equals(requestedStatus)) {
                if (!repository.hasCompleteEvidence(actor.tenantId(), id)) {
                    throw new ProcessRejectedException("signature evidence set is incomplete");
                }
            }
            if (SequentialStateMachine.CLOSED.equals(requestedStatus) && !"SIGNED".equals(current.signStatus())) {
                throw new ProcessRejectedException(
                        "signature envelope cannot close before signed evidence is verified");
            }
            int updated = repository.updateStatus(
                    actor.tenantId(),
                    id,
                    expectedVersion,
                    requestedStatus,
                    SequentialStateMachine.CLOSED.equals(requestedStatus) ? Instant.now() : null,
                    actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("signature envelope concurrent update conflict");
            }
            return required(actor.tenantId(), id);
        });
    }

    public Envelope verifyCallback(
            DatabaseSecurityContext actor,
            UUID id,
            int expectedVersion,
            String providerEventKey,
            String callbackHash,
            CallbackEvidence evidence) {
        validateEvidence(evidence);
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.userId(),
                    providerEventKey,
                    callbackHash,
                    "document.signature_callback:" + id,
                    id,
                    IDEMPOTENCY_TTL);
            Envelope current = required(actor.tenantId(), id);
            if (claim.existing() && atOrAfterVerified(current.status())) {
                return current;
            }
            if (current.versionNo() != expectedVersion || !"S06".equals(current.status())) {
                throw new ProcessRejectedException("provider callback is not legal for current signature state");
            }
            files.assertSafe(actor.tenantId(), evidence.completedFileId(), evidence.completedFileSha256());
            files.assertSafe(actor.tenantId(), evidence.certificateFileId(), null);
            files.assertSafe(actor.tenantId(), evidence.timestampFileId(), null);
            files.assertSafe(actor.tenantId(), evidence.callbackEvidenceFileId(), callbackHash);
            provider().verify(current, evidence);
            repository.recordEvidence(actor.tenantId(), id, evidence, actor.userId());
            int updated = repository.updateVerified(
                    actor.tenantId(), id, expectedVersion, evidence.completedFileId(), actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("signature callback concurrent update conflict");
            }
            Envelope verified = required(actor.tenantId(), id);
            if (!repository.hasCompleteEvidence(actor.tenantId(), id)) {
                throw new ProcessRejectedException(
                        "signature provider callback did not produce a complete evidence set");
            }
            return verified;
        });
    }

    private SignatureProviderCapability provider() {
        if (providers.size() != 1) {
            throw new ProcessRejectedException("signature provider capability is unavailable or ambiguous");
        }
        return providers.getFirst();
    }

    private Envelope required(UUID tenantId, UUID id) {
        return repository
                .find(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("signature envelope not found"));
    }

    private static boolean atOrAfterVerified(String status) {
        return List.of("S07", "S08", "S09", SequentialStateMachine.CLOSED).contains(status);
    }

    private static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.sourceFileId() == null
                || blank(command.documentHash())
                || command.documentHash().length() < 32
                || blank(command.signingOrder())
                || blank(command.authenticationMethod())
                || command.businessDate() == null
                || blank(command.documentType())
                || blank(command.resultSummary())
                || command.parties() == null
                || command.parties().isEmpty()) {
            throw new ProcessRejectedException("required signature envelope fields are missing");
        }
        long uniqueOrders = command.parties().stream()
                .map(PartyCommand::signOrder)
                .distinct()
                .count();
        if (uniqueOrders != command.parties().size()
                || command.parties().stream().anyMatch(p -> p.signOrder() <= 0 || blank(p.partyName()))) {
            throw new ProcessRejectedException("signature party order/name is invalid");
        }
    }

    private static void validateEvidence(CallbackEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.completedFileId() == null
                || blank(evidence.completedFileSha256())
                || evidence.certificateFileId() == null
                || evidence.timestampFileId() == null
                || evidence.callbackEvidenceFileId() == null
                || blank(evidence.verificationResult())
                || evidence.partyEvidence() == null
                || evidence.partyEvidence().isEmpty()
                || evidence.partyEvidence().stream().anyMatch(p -> p.partyId() == null || blank(p.evidenceNo()))) {
            throw new ProcessRejectedException("signature callback evidence is incomplete");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public interface Repository {
        void insert(Envelope envelope, UUID sourceFileId, List<PartyCommand> parties, UUID actorId);

        Optional<Envelope> find(UUID tenantId, UUID id);

        List<Party> parties(UUID tenantId, UUID envelopeId);

        int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId);

        void recordEvidence(UUID tenantId, UUID envelopeId, CallbackEvidence evidence, UUID actorId);

        int updateVerified(UUID tenantId, UUID id, int expectedVersion, UUID completedFileId, UUID actorId);

        boolean hasCompleteEvidence(UUID tenantId, UUID envelopeId);
    }

    public interface FileEvidenceCapability {
        void assertSafe(UUID tenantId, UUID fileId, String expectedSha256);
    }

    public interface SignatureProviderCapability {
        void initiate(Envelope envelope, List<Party> parties);

        void verify(Envelope envelope, CallbackEvidence evidence);
    }

    public record PartyCommand(
            String partyType, UUID partyId, String partyName, int signOrder, String authenticationMethod) {}

    public record Party(UUID id, UUID partyId, String partyType, int signOrder, String signStatus, String evidenceNo) {}

    public record PartyEvidence(UUID partyId, String evidenceNo) {}

    public record CallbackEvidence(
            UUID completedFileId,
            String completedFileSha256,
            UUID certificateFileId,
            UUID timestampFileId,
            UUID callbackEvidenceFileId,
            String verificationResult,
            List<PartyEvidence> partyEvidence) {}

    public record CreateCommand(
            UUID sourceFileId,
            String documentHash,
            String templateVersion,
            String signingOrder,
            Instant signDeadlineAt,
            String authenticationMethod,
            LocalDate businessDate,
            String documentType,
            String resultSummary,
            List<PartyCommand> parties) {}

    public record Envelope(
            UUID id,
            UUID tenantId,
            String businessNo,
            String envelopeNo,
            String status,
            int versionNo,
            String documentHash,
            String templateVersion,
            String signingOrder,
            Instant signDeadlineAt,
            String signStatus,
            UUID completedFileId,
            String authenticationMethod,
            LocalDate businessDate,
            String documentType,
            String resultSummary,
            Instant actualStartAt,
            Instant actualEndAt,
            UUID createdBy) {}
}
