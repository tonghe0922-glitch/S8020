package cn.shangjingu.platform.audit;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.core.process.SequentialStateMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class SensitiveExportService {
    public static final String PROCESS_CODE = "P019";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    private static final SequentialStateMachine STATES = new SequentialStateMachine(
            List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final Repository repository;
    private final ObjectMapper mapper;
    private final List<ExportPolicyCapability> policies;
    private final List<AuditedDownloadCapability> downloads;

    public SensitiveExportService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            Repository repository,
            ObjectMapper mapper,
            List<ExportPolicyCapability> policies,
            List<AuditedDownloadCapability> downloads) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.repository = repository;
        this.mapper = mapper;
        this.policies = List.copyOf(policies);
        this.downloads = List.copyOf(downloads);
    }

    public ExportRequest create(DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        validate(command);
        String dataScope = json(command.dataScope(), "dataScope");
        String fieldScope = json(command.fieldScope(), "fieldScope");
        return transactions.required(actor, () -> {
            UUID proposed = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(), actor.userId(), idempotencyKey, requestHash,
                    "audit.data_export_request", proposed, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());
            ExportRequest request = new ExportRequest(
                    proposed, actor.tenantId(), numbers.next(actor.tenantId(), actor.userId(), PROCESS_CODE),
                    STATES.initial(), 0, command.exportType(), dataScope, fieldScope, command.purpose(),
                    command.approvalLevel(), null, null, null, 0, Instant.now(), null, command.businessDate(),
                    command.environment(), command.resultSummary(), command.rollbackPlan(), command.systemServiceName(),
                    command.techImpactScope(), command.techRiskLevel(), actor.userId());
            repository.insert(request, command.items(), actor.userId());
            return request;
        });
    }

    public Optional<ExportRequest> find(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public List<ExportItem> items(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.items(actor.tenantId(), id));
    }

    public ExportRequest advance(DatabaseSecurityContext actor, UUID id, int expectedVersion, String requestedStatus) {
        return transactions.required(actor, () -> {
            ExportRequest current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            if (Objects.equals(current.status(), requestedStatus)) return current;
            STATES.requireTransition(current.status(), requestedStatus);
            if (List.of("S06", "S07", "S08", "S09").contains(requestedStatus)) {
                throw new ProcessRejectedException("sensitive export state requires dedicated server capability");
            }
            if ("S02".equals(requestedStatus)) policy().validate(PolicyStage.DATA_SCOPE, current);
            else if ("S03".equals(requestedStatus)) policy().validate(PolicyStage.FIELD_SENSITIVITY, current);
            else if ("S04".equals(requestedStatus)) policy().validate(PolicyStage.PURPOSE_APPROVAL, current);
            else if ("S05".equals(requestedStatus)) repository.enqueueGeneration(current, expectedVersion + 1, actor.userId());
            int updated = repository.updateStatus(actor.tenantId(), id, expectedVersion, requestedStatus, null, actor.userId());
            if (updated != 1) throw new ProcessRejectedException("sensitive export concurrent update conflict");
            return required(actor.tenantId(), id);
        });
    }

    public ExportRequest recordGenerated(DatabaseSecurityContext actor, UUID id, int expectedVersion, GenerationResult result) {
        Objects.requireNonNull(result, "result");
        if (result.fileId() == null || blank(result.watermarkText()) || result.expireAt() == null
                || !result.expireAt().isAfter(Instant.now())) {
            throw new ProcessRejectedException("generated export evidence is incomplete or expired");
        }
        return transactions.required(actor, () -> {
            ExportRequest current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            if (!"S05".equals(current.status())) throw new ProcessRejectedException("generated export result is not legal for current state");
            repository.assertSafeFile(actor.tenantId(), result.fileId());
            int updated = repository.recordGenerated(actor.tenantId(), id, expectedVersion, result, actor.userId());
            if (updated != 1) throw new ProcessRejectedException("generated export concurrent update conflict");
            return required(actor.tenantId(), id);
        });
    }

    /** Call only after PHASE-04 Step-Up ticket has been successfully consumed by the HTTP boundary. */
    public DownloadGrant issueAuditedDownload(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        return transactions.required(actor, () -> {
            ExportRequest current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            if (!"S06".equals(current.status()) || current.fileId() == null || current.expireAt() == null
                    || !current.expireAt().isAfter(Instant.now()) || blank(current.watermarkText())) {
                throw new ProcessRejectedException("sensitive export is not ready for second-authenticated download");
            }
            if (repository.updateStatus(actor.tenantId(), id, expectedVersion, "S07", null, actor.userId()) != 1) {
                throw new ProcessRejectedException("second-authentication state concurrent update conflict");
            }
            AuditedDownloadCapability capability = download();
            DownloadGrant grant = capability.issue(current);
            if (grant == null || blank(grant.location()) || grant.expiresAt() == null
                    || !grant.expiresAt().isAfter(Instant.now()) || grant.expiresAt().isAfter(current.expireAt())) {
                if (grant != null) capability.revoke(grant);
                throw new ProcessRejectedException("download capability returned an invalid grant");
            }
            int updated = repository.recordDownloadGrant(actor.tenantId(), id, expectedVersion + 1, actor.userId());
            if (updated != 1) {
                capability.revoke(grant);
                throw new ProcessRejectedException("download grant concurrent update conflict");
            }
            return grant;
        });
    }

    public ExportRequest expireAndDestroy(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        return transactions.required(actor, () -> {
            ExportRequest current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            if (!"S08".equals(current.status())) throw new ProcessRejectedException("sensitive export is not ready for expiry/destruction");
            if (current.fileId() == null) throw new ProcessRejectedException("sensitive export file reference is missing");
            download().destroy(current);
            int updated = repository.updateStatus(actor.tenantId(), id, expectedVersion, "S09", Instant.now(), actor.userId());
            if (updated != 1) throw new ProcessRejectedException("sensitive export expiry concurrent update conflict");
            return required(actor.tenantId(), id);
        });
    }

    public ExportRequest close(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        return transactions.required(actor, () -> {
            ExportRequest current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            STATES.requireTransition(current.status(), SequentialStateMachine.CLOSED);
            int updated = repository.updateStatus(actor.tenantId(), id, expectedVersion, SequentialStateMachine.CLOSED, Instant.now(), actor.userId());
            if (updated != 1) throw new ProcessRejectedException("sensitive export close concurrent update conflict");
            return required(actor.tenantId(), id);
        });
    }

    private ExportPolicyCapability policy() {
        if (policies.size() != 1) throw new ProcessRejectedException("sensitive export policy capability is unavailable or ambiguous");
        return policies.getFirst();
    }

    private AuditedDownloadCapability download() {
        if (downloads.size() != 1) throw new ProcessRejectedException("audited download capability is unavailable or ambiguous");
        return downloads.getFirst();
    }

    private ExportRequest required(UUID tenantId, UUID id) {
        return repository.find(tenantId, id).orElseThrow(() -> new ProcessRejectedException("sensitive export request not found"));
    }

    private static void requireVersion(ExportRequest request, int expectedVersion) {
        if (request.versionNo() != expectedVersion) throw new ProcessRejectedException("sensitive export version conflict");
    }

    private String json(Object value, String field) {
        if (value == null) throw new ProcessRejectedException(field + " is required");
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException(field + " is not serializable JSON", ex); }
    }

    private static void validate(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        if (blank(command.exportType()) || command.dataScope() == null || command.fieldScope() == null
                || blank(command.purpose()) || blank(command.approvalLevel()) || command.businessDate() == null
                || blank(command.environment()) || blank(command.resultSummary()) || blank(command.systemServiceName())
                || blank(command.techImpactScope()) || blank(command.techRiskLevel()) || command.items() == null || command.items().isEmpty()) {
            throw new ProcessRejectedException("required sensitive export fields are missing");
        }
        if (command.items().stream().anyMatch(item -> blank(item.fieldCode()) || item.itemSeq() < 0)) {
            throw new ProcessRejectedException("sensitive export field items are invalid");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public interface Repository {
        void insert(ExportRequest request, List<ExportItem> items, UUID actorId);
        Optional<ExportRequest> find(UUID tenantId, UUID id);
        List<ExportItem> items(UUID tenantId, UUID id);
        void assertSafeFile(UUID tenantId, UUID fileId);
        int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId);
        void enqueueGeneration(ExportRequest request, int targetVersion, UUID actorId);
        int recordGenerated(UUID tenantId, UUID id, int expectedVersion, GenerationResult result, UUID actorId);
        int recordDownloadGrant(UUID tenantId, UUID id, int expectedVersion, UUID actorId);
    }

    public interface ExportPolicyCapability { void validate(PolicyStage stage, ExportRequest request); }
    public interface SensitiveExportGenerator { String exportType(); GenerationResult generate(ExportRequest request, List<ExportItem> items); }
    public interface AuditedDownloadCapability { DownloadGrant issue(ExportRequest request); void revoke(DownloadGrant grant); void destroy(ExportRequest request); }
    public enum PolicyStage { DATA_SCOPE, FIELD_SENSITIVITY, PURPOSE_APPROVAL }
    public record ExportItem(int itemSeq, String fieldCode, String itemKey, String itemName) {}
    public record GenerationResult(UUID fileId, String watermarkText, Instant expireAt) {}
    public record DownloadGrant(String location, Instant expiresAt) {}
    public record CreateCommand(String exportType, Object dataScope, Object fieldScope, String purpose, String approvalLevel,
            LocalDate businessDate, String environment, String resultSummary, String rollbackPlan, String systemServiceName,
            String techImpactScope, String techRiskLevel, List<ExportItem> items) {}
    public record ExportRequest(UUID id, UUID tenantId, String businessNo, String status, int versionNo, String exportType,
            String dataScopeJson, String fieldScopeJson, String purpose, String approvalLevel, String watermarkText,
            Instant expireAt, UUID fileId, int downloadCount, Instant actualStartAt, Instant actualEndAt,
            LocalDate businessDate, String environment, String resultSummary, String rollbackPlan, String systemServiceName,
            String techImpactScope, String techRiskLevel, UUID createdBy) {}
}
