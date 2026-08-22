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
public class DataQualityRepairService {
    public static final String PROCESS_CODE = "P020";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    private static final SequentialStateMachine STATES =
            new SequentialStateMachine(List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final Repository repository;
    private final ObjectMapper mapper;
    private final List<RepairGovernanceCapability> governance;
    private final List<RepairHandler> handlers;
    private final List<CompensationCapability> compensations;
    private final List<VerificationCapability> verifiers;

    public DataQualityRepairService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            Repository repository,
            ObjectMapper mapper,
            List<RepairGovernanceCapability> governance,
            List<RepairHandler> handlers,
            List<CompensationCapability> compensations,
            List<VerificationCapability> verifiers) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.repository = repository;
        this.mapper = mapper;
        this.governance = List.copyOf(governance);
        this.handlers = List.copyOf(handlers);
        this.compensations = List.copyOf(compensations);
        this.verifiers = List.copyOf(verifiers);
    }

    public QualityIssue create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        validateCreate(command);
        String before = json(command.beforeSnapshot(), "beforeSnapshot");
        return transactions.required(actor, () -> {
            UUID proposed = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.userId(),
                    idempotencyKey,
                    requestHash,
                    "audit.data_quality_issue",
                    proposed,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());
            QualityIssue issue = new QualityIssue(
                    proposed,
                    actor.tenantId(),
                    numbers.next(actor.tenantId(), actor.userId(), PROCESS_CODE),
                    STATES.initial(),
                    0,
                    command.ruleCode(),
                    command.objectType(),
                    command.objectId(),
                    command.issueType(),
                    command.severity(),
                    before,
                    null,
                    null,
                    null,
                    null,
                    Instant.now(),
                    null,
                    command.businessDate(),
                    command.employeeEventType(),
                    command.environment(),
                    command.resultSummary(),
                    command.systemServiceName(),
                    command.techImpactScope(),
                    command.techRiskLevel(),
                    actor.userId());
            repository.insert(issue, command.items(), actor.userId());
            return issue;
        });
    }

    public Optional<QualityIssue> find(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public List<QualityItem> items(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.items(actor.tenantId(), id));
    }

    public QualityIssue advance(DatabaseSecurityContext actor, UUID id, int expectedVersion, String requestedStatus) {
        return transactions.required(actor, () -> {
            QualityIssue current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            if (Objects.equals(current.status(), requestedStatus)) return current;
            STATES.requireTransition(current.status(), requestedStatus);
            if (List.of("S05", "S06", "S08").contains(requestedStatus)) {
                throw new ProcessRejectedException("data-quality repair state requires dedicated server capability");
            }
            if ("S03".equals(requestedStatus))
                governance().validate(GovernanceStage.AUTHORITATIVE_SOURCE, current, null);
            else if ("S04".equals(requestedStatus))
                governance().validate(GovernanceStage.IMPACT_ANALYSIS, current, null);
            else if ("S07".equals(requestedStatus)) compensation().compensate(current);
            int updated = repository.updateStatus(
                    actor.tenantId(),
                    id,
                    expectedVersion,
                    requestedStatus,
                    SequentialStateMachine.CLOSED.equals(requestedStatus) ? Instant.now() : null,
                    actor.userId());
            if (updated != 1) throw new ProcessRejectedException("data-quality issue concurrent update conflict");
            return required(actor.tenantId(), id);
        });
    }

    public QualityIssue approvePlan(DatabaseSecurityContext actor, UUID id, int expectedVersion, Object plan) {
        if (actor.userId() == null || plan == null)
            throw new ProcessRejectedException("repair plan reviewer and plan are required");
        String planJson = json(plan, "repairPlan");
        return transactions.required(actor, () -> {
            QualityIssue current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            STATES.requireTransition(current.status(), "S05");
            governance().validate(GovernanceStage.PLAN_APPROVAL, current, planJson);
            int updated = repository.approvePlan(actor.tenantId(), id, expectedVersion, planJson, actor.userId());
            if (updated != 1) throw new ProcessRejectedException("repair plan approval concurrent conflict");
            return required(actor.tenantId(), id);
        });
    }

    public QualityIssue executeRepair(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        if (actor.userId() == null) throw new ProcessRejectedException("repair executor identity is required");
        return transactions.required(actor, () -> {
            QualityIssue current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            STATES.requireTransition(current.status(), "S06");
            RepairControl control = repository.repairControl(actor.tenantId(), id);
            if (control == null || control.reviewerUserId() == null || blank(control.planJson())) {
                throw new ProcessRejectedException("approved repair plan/reviewer evidence is missing");
            }
            if (actor.userId().equals(control.reviewerUserId())) {
                throw new ProcessRejectedException("repair reviewer and executor must be different users");
            }
            RepairHandler handler = handler(current);
            RepairResult result = handler.execute(current, control.planJson());
            if (result == null || result.afterSnapshot() == null || blank(result.resolutionAction())) {
                throw new ProcessRejectedException("repair handler returned incomplete evidence");
            }
            String after = json(result.afterSnapshot(), "afterSnapshot");
            int updated = repository.recordRepair(
                    actor.tenantId(), id, expectedVersion, after, result.resolutionAction(), actor.userId());
            if (updated != 1) throw new ProcessRejectedException("repair execution concurrent conflict");
            return required(actor.tenantId(), id);
        });
    }

    public QualityIssue verify(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        return transactions.required(actor, () -> {
            QualityIssue current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            STATES.requireTransition(current.status(), "S08");
            if (blank(current.beforeSnapshotJson()) || blank(current.afterSnapshotJson())) {
                throw new ProcessRejectedException("before/after repair evidence is incomplete");
            }
            verifier().verify(current);
            int updated = repository.recordVerified(actor.tenantId(), id, expectedVersion, actor.userId());
            if (updated != 1) throw new ProcessRejectedException("repair verification concurrent conflict");
            return required(actor.tenantId(), id);
        });
    }

    public QualityIssue close(DatabaseSecurityContext actor, UUID id, int expectedVersion) {
        return transactions.required(actor, () -> {
            QualityIssue current = required(actor.tenantId(), id);
            requireVersion(current, expectedVersion);
            STATES.requireTransition(current.status(), SequentialStateMachine.CLOSED);
            if (current.verifiedAt() == null
                    || blank(current.afterSnapshotJson())
                    || blank(current.resolutionAction())) {
                throw new ProcessRejectedException("data-quality issue close evidence is incomplete");
            }
            int updated = repository.updateStatus(
                    actor.tenantId(),
                    id,
                    expectedVersion,
                    SequentialStateMachine.CLOSED,
                    Instant.now(),
                    actor.userId());
            if (updated != 1) throw new ProcessRejectedException("data-quality issue close concurrent conflict");
            return required(actor.tenantId(), id);
        });
    }

    private RepairGovernanceCapability governance() {
        if (governance.size() != 1)
            throw new ProcessRejectedException("repair governance capability is unavailable or ambiguous");
        return governance.getFirst();
    }

    private RepairHandler handler(QualityIssue issue) {
        List<RepairHandler> matches = handlers.stream()
                .filter(h -> h.supports(issue.objectType(), issue.issueType()))
                .toList();
        if (matches.size() != 1)
            throw new ProcessRejectedException("repair handler is unavailable or ambiguous for issue type");
        return matches.getFirst();
    }

    private CompensationCapability compensation() {
        if (compensations.size() != 1)
            throw new ProcessRejectedException("repair compensation capability is unavailable or ambiguous");
        return compensations.getFirst();
    }

    private VerificationCapability verifier() {
        if (verifiers.size() != 1)
            throw new ProcessRejectedException("repair verification capability is unavailable or ambiguous");
        return verifiers.getFirst();
    }

    private QualityIssue required(UUID tenantId, UUID id) {
        return repository
                .find(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("data-quality issue not found"));
    }

    private static void requireVersion(QualityIssue issue, int expectedVersion) {
        if (issue.versionNo() != expectedVersion)
            throw new ProcessRejectedException("data-quality issue version conflict");
    }

    private String json(Object value, String field) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ProcessRejectedException(field + " is not serializable JSON", ex);
        }
    }

    private static void validateCreate(CreateCommand c) {
        Objects.requireNonNull(c, "command");
        if (blank(c.ruleCode())
                || blank(c.objectType())
                || blank(c.issueType())
                || blank(c.severity())
                || c.beforeSnapshot() == null
                || c.businessDate() == null
                || blank(c.employeeEventType())
                || blank(c.environment())
                || blank(c.resultSummary())
                || blank(c.systemServiceName())
                || blank(c.techImpactScope())
                || blank(c.techRiskLevel())
                || c.items() == null) {
            throw new ProcessRejectedException("required data-quality issue fields are missing");
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    public interface Repository {
        void insert(QualityIssue issue, List<QualityItem> items, UUID actorId);

        Optional<QualityIssue> find(UUID tenantId, UUID id);

        List<QualityItem> items(UUID tenantId, UUID id);

        int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId);

        int approvePlan(UUID tenantId, UUID id, int expectedVersion, String planJson, UUID reviewerUserId);

        RepairControl repairControl(UUID tenantId, UUID id);

        int recordRepair(
                UUID tenantId,
                UUID id,
                int expectedVersion,
                String afterSnapshotJson,
                String resolutionAction,
                UUID executorUserId);

        int recordVerified(UUID tenantId, UUID id, int expectedVersion, UUID actorId);
    }

    public interface RepairGovernanceCapability {
        void validate(GovernanceStage stage, QualityIssue issue, String planJson);
    }

    public interface RepairHandler {
        boolean supports(String objectType, String issueType);

        RepairResult execute(QualityIssue issue, String approvedPlanJson);
    }

    public interface CompensationCapability {
        void compensate(QualityIssue issue);
    }

    public interface VerificationCapability {
        void verify(QualityIssue issue);
    }

    public enum GovernanceStage {
        AUTHORITATIVE_SOURCE,
        IMPACT_ANALYSIS,
        PLAN_APPROVAL
    }

    public record QualityItem(int itemSeq, String fieldCode, String itemKey, String itemName, String valueText) {}

    public record RepairControl(UUID reviewerUserId, String planJson) {}

    public record RepairResult(Object afterSnapshot, String resolutionAction) {}

    public record CreateCommand(
            String ruleCode,
            String objectType,
            UUID objectId,
            String issueType,
            String severity,
            Object beforeSnapshot,
            LocalDate businessDate,
            String employeeEventType,
            String environment,
            String resultSummary,
            String systemServiceName,
            String techImpactScope,
            String techRiskLevel,
            List<QualityItem> items) {}

    public record QualityIssue(
            UUID id,
            UUID tenantId,
            String businessNo,
            String status,
            int versionNo,
            String ruleCode,
            String objectType,
            UUID objectId,
            String issueType,
            String severity,
            String beforeSnapshotJson,
            String afterSnapshotJson,
            String rootCause,
            String resolutionAction,
            Instant verifiedAt,
            Instant actualStartAt,
            Instant actualEndAt,
            LocalDate businessDate,
            String employeeEventType,
            String environment,
            String resultSummary,
            String systemServiceName,
            String techImpactScope,
            String techRiskLevel,
            UUID createdBy) {}
}
