package cn.shangjingu.platform.integration;

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
public class DataImportService {
    public static final String PROCESS_CODE = "P018";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    private static final SequentialStateMachine STATES = new SequentialStateMachine(
            List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final Repository repository;
    private final List<ValidationCapability> validators;

    public DataImportService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            Repository repository,
            List<ValidationCapability> validators) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.repository = repository;
        this.validators = List.copyOf(validators);
    }

    public DataImportJob create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        validateCreate(command);
        return transactions.required(actor, () -> {
            repository.assertSafeFile(actor.tenantId(), command.sourceFileId());
            UUID proposed = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.userId(),
                    idempotencyKey,
                    requestHash,
                    "integration.data_import_job",
                    proposed,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return required(actor.tenantId(), claim.resourceId());
            }
            String businessNo = numbers.next(actor.tenantId(), actor.userId(), PROCESS_CODE);
            DataImportJob job = new DataImportJob(
                    proposed,
                    actor.tenantId(),
                    businessNo,
                    STATES.initial(),
                    0,
                    command.importType(),
                    command.sourceFileId(),
                    command.templateVersion(),
                    0,
                    0,
                    0,
                    null,
                    null,
                    Instant.now(),
                    null,
                    command.businessDate(),
                    command.environment(),
                    command.resultSummary(),
                    command.rollbackPlan(),
                    command.systemServiceName(),
                    command.techImpactScope(),
                    command.techRiskLevel(),
                    actor.userId());
            repository.insert(job, actor.userId());
            return job;
        });
    }

    public Optional<DataImportJob> find(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public DataImportJob advance(DatabaseSecurityContext actor, UUID id, int expectedVersion, String requestedStatus) {
        return transactions.required(actor, () -> {
            DataImportJob current = required(actor.tenantId(), id);
            if (current.versionNo() != expectedVersion) {
                throw new ProcessRejectedException("data import version conflict");
            }
            if (Objects.equals(current.status(), requestedStatus)) {
                return current;
            }
            STATES.requireTransition(current.status(), requestedStatus);
            if ("S06".equals(requestedStatus)) {
                throw new ProcessRejectedException("S06 requires a persisted validation/difference preview");
            }
            if ("S09".equals(requestedStatus)) {
                throw new ProcessRejectedException("S09 may only be entered by asynchronous execution result");
            }
            if ("S03".equals(requestedStatus)) {
                validator().validate(ValidationStage.FORMAT, current);
            } else if ("S04".equals(requestedStatus)) {
                validator().validate(ValidationStage.BUSINESS, current);
            } else if ("S05".equals(requestedStatus)) {
                validator().validate(ValidationStage.PERMISSION_SCOPE, current);
            }
            if ("S08".equals(requestedStatus)) {
                repository.enqueueExecution(current, expectedVersion + 1, actor.userId());
            }
            int updated = repository.updateStatus(
                    actor.tenantId(),
                    id,
                    expectedVersion,
                    requestedStatus,
                    SequentialStateMachine.CLOSED.equals(requestedStatus) ? Instant.now() : null,
                    actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("data import concurrent update conflict");
            }
            return required(actor.tenantId(), id);
        });
    }

    public DataImportJob savePreview(
            DatabaseSecurityContext actor, UUID id, int expectedVersion, ValidationPreview preview) {
        Objects.requireNonNull(preview, "preview");
        if (preview.totalRows() < 0
                || preview.items() == null
                || preview.items().size() > preview.totalRows()) {
            throw new ProcessRejectedException("data import preview counters are invalid");
        }
        return transactions.required(actor, () -> {
            DataImportJob current = required(actor.tenantId(), id);
            if (current.versionNo() != expectedVersion || !"S05".equals(current.status())) {
                throw new ProcessRejectedException("data import preview is not legal for current state");
            }
            validator().validatePreview(current, preview);
            int updated = repository.savePreview(actor.tenantId(), id, expectedVersion, preview, actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("data import preview concurrent update conflict");
            }
            return required(actor.tenantId(), id);
        });
    }

    public DataImportJob recordExecutionResult(
            DatabaseSecurityContext actor, UUID id, int expectedVersion, ExecutionResult result) {
        Objects.requireNonNull(result, "result");
        if (result.totalRows() < 0
                || result.successRows() < 0
                || result.failedRows() < 0
                || result.successRows() + result.failedRows() != result.totalRows()
                || blank(result.resultSummary())) {
            throw new ProcessRejectedException("data import execution counters/result are invalid");
        }
        return transactions.required(actor, () -> {
            DataImportJob current = required(actor.tenantId(), id);
            if (current.versionNo() != expectedVersion || !"S08".equals(current.status())) {
                throw new ProcessRejectedException("data import execution result is not legal for current state");
            }
            if (result.resultFileId() != null) {
                repository.assertSafeFile(actor.tenantId(), result.resultFileId());
            }
            int updated =
                    repository.recordExecutionResult(actor.tenantId(), id, expectedVersion, result, actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("data import execution result concurrent conflict");
            }
            return required(actor.tenantId(), id);
        });
    }

    public List<ImportItem> items(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.items(actor.tenantId(), id));
    }

    private ValidationCapability validator() {
        if (validators.size() != 1) {
            throw new ProcessRejectedException("data import validation capability is unavailable or ambiguous");
        }
        return validators.getFirst();
    }

    private DataImportJob required(UUID tenantId, UUID id) {
        return repository
                .find(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("data import job not found"));
    }

    private static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        if (blank(command.importType())
                || command.sourceFileId() == null
                || blank(command.templateVersion())
                || command.businessDate() == null
                || blank(command.environment())
                || blank(command.resultSummary())
                || blank(command.systemServiceName())
                || blank(command.techImpactScope())
                || blank(command.techRiskLevel())) {
            throw new ProcessRejectedException("required data import fields are missing");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public interface Repository {
        void insert(DataImportJob job, UUID actorId);

        Optional<DataImportJob> find(UUID tenantId, UUID id);

        List<ImportItem> items(UUID tenantId, UUID id);

        void assertSafeFile(UUID tenantId, UUID fileId);

        int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId);

        int savePreview(UUID tenantId, UUID id, int expectedVersion, ValidationPreview preview, UUID actorId);

        void enqueueExecution(DataImportJob job, int targetVersion, UUID actorId);

        int recordExecutionResult(UUID tenantId, UUID id, int expectedVersion, ExecutionResult result, UUID actorId);
    }

    public interface ValidationCapability {
        void validate(ValidationStage stage, DataImportJob job);

        void validatePreview(DataImportJob job, ValidationPreview preview);
    }

    public interface ImportExecutor {
        String importType();

        ExecutionResult execute(DataImportJob job, List<ImportItem> items);
    }

    public enum ValidationStage {
        FORMAT,
        BUSINESS,
        PERMISSION_SCOPE
    }

    public record ImportItem(int itemSeq, String fieldCode, String itemKey, String itemName, String valueText) {}

    public record ValidationPreview(int totalRows, String code, String summary, List<ImportItem> items) {}

    public record ExecutionResult(
            int totalRows, int successRows, int failedRows, UUID resultFileId, String resultSummary) {}

    public record CreateCommand(
            String importType,
            UUID sourceFileId,
            String templateVersion,
            LocalDate businessDate,
            String environment,
            String resultSummary,
            String rollbackPlan,
            String systemServiceName,
            String techImpactScope,
            String techRiskLevel) {}

    public record DataImportJob(
            UUID id,
            UUID tenantId,
            String businessNo,
            String status,
            int versionNo,
            String importType,
            UUID sourceFileId,
            String templateVersion,
            int totalRows,
            int successRows,
            int failedRows,
            String validationCode,
            UUID resultFileId,
            Instant actualStartAt,
            Instant actualEndAt,
            LocalDate businessDate,
            String environment,
            String resultSummary,
            String rollbackPlan,
            String systemServiceName,
            String techImpactScope,
            String techRiskLevel,
            UUID createdBy) {}
}
