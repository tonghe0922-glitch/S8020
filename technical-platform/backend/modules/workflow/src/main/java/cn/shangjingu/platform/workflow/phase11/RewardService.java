package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P013 reward lifecycle with evidence uniqueness and exactly-once point effects. */
@Service
public class RewardService {
    private static final Phase11Process PROCESS = Phase11Process.P013;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository phase11Repository;
    private final RewardRepository rewards;
    private final ObjectMapper mapper;

    public RewardService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository phase11Repository,
            RewardRepository rewards,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.phase11Repository = phase11Repository;
        this.rewards = rewards;
        this.mapper = mapper;
    }

    public Phase11Record create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        return transactions.required(actor, () -> {
            if (!phase11Repository.activeEmployeeInOrg(
                    actor.tenantId(), command.ownerCenterId(), command.ownerEmployeeId())) {
                throw rejected("reward recipient must be active in the owner center");
            }
            if (!rewards.sourceFactAvailable(
                    actor.tenantId(), command.sourceFactKey().trim())) {
                throw rejected("source fact has already produced a reward case");
            }
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    PROCESS.table(),
                    proposedId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return required(actor.tenantId(), claim.resourceId());
            }
            Phase11Record draft = draft(actor, claim.resourceId(), command);
            rewards.insert(draft, command, actor.employeeId());
            Phase11WorkflowCoordinator.Started started =
                    workflow.start(actor, PROCESS, draft, command.toCreateData(), idempotencyKey);
            if (rewards.bindWorkflow(
                            actor.tenantId(),
                            draft.id(),
                            0,
                            started.workflowInstanceId(),
                            started.currentNodeCode(),
                            PROCESS.labelFor(started.currentNodeCode()),
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent create transition conflict");
            }
            Phase11Record created = required(actor.tenantId(), draft.id());
            emit(actor, created, PROCESS.initialAction());
            return created;
        });
    }

    public Phase11Record act(
            DatabaseSecurityContext actor,
            UUID rewardId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P013 action command is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            Phase11Record current = required(actor.tenantId(), rewardId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    PROCESS.table() + ".action." + action.toLowerCase(Locale.ROOT),
                    rewardId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.versionNo() != command.expectedVersion()) {
                throw rejected("version conflict");
            }
            Phase11Process.Step step = PROCESS.requireTransition(current.currentNodeCode(), action);
            validateActor(current, actor.employeeId());
            validateAction(current, action, command);
            WorkflowRuntimeService.Result moved =
                    workflow.advance(actor, PROCESS, current, action, command.reason(), idempotencyKey);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected("workflow target does not match frozen contract");
            }
            UUID pointEffectId = null;
            if ("EXECUTE_REWARD".equals(action)) {
                pointEffectId =
                        rewards.createPointEffect(current, command.summary().trim(), actor.employeeId());
            }
            if (rewards.advance(
                            current,
                            action,
                            step.targetNode(),
                            PROCESS.labelFor(step.targetNode()),
                            command,
                            pointEffectId,
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent aggregate transition conflict");
            }
            Phase11Record result = required(actor.tenantId(), rewardId);
            emit(actor, result, action);
            return result;
        });
    }

    public Optional<Phase11Record> find(DatabaseSecurityContext actor, UUID rewardId) {
        requireActor(actor);
        return transactions.required(actor, () -> rewards.find(actor.tenantId(), rewardId));
    }

    public List<Phase11Record> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> rewards.list(actor.tenantId()));
    }

    private void validateAction(Phase11Record current, String action, ActionCommand command) {
        requireText(command.summary(), "summary");
        if ("APPROVE_REWARD".equals(action)) {
            requireText(command.decision(), "decision");
        }
        if ("CHECK_DUPLICATE_IMPACT".equals(action)
                && !rewards.executionEffectAbsent(current.tenantId(), current.id())) {
            throw rejected("reward impact already exists");
        }
        if ("EXECUTE_REWARD".equals(action)) {
            BigDecimal benefit = decimal(current.details(), "benefitAmount");
            if (benefit.signum() > 0
                    && !rewards.paidFinanceReference(current.tenantId(), command.financeReferenceId(), benefit)) {
                throw rejected("authoritative paid finance reference is required");
            }
            if (!rewards.executionEffectAbsent(current.tenantId(), current.id())) {
                throw rejected("reward impact already exists");
            }
        }
        if ("RECORD_RECEIPTS".equals(action)) {
            requireText(command.receiptReference(), "receiptReference");
        }
        if ("ARCHIVE".equals(action)) {
            requireDetail(current.details(), "rewardExecutedAt");
            requireDetail(current.details(), "receiptsRecordedAt");
        }
    }

    static void validateActor(Phase11Record current, UUID actorEmployeeId) {
        if (actorEmployeeId.equals(current.ownerEmployeeId())) {
            throw rejected("self review, approval and reward execution are forbidden");
        }
    }

    static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P013 create command is required");
        requireText(command.subject(), "subject");
        requireText(command.reason(), "reason");
        requireText(command.factSummary(), "factSummary");
        requireText(command.sourceFactKey(), "sourceFactKey");
        requireText(command.contentVersion(), "contentVersion");
        requireText(command.periodNo(), "periodNo");
        requireText(command.impactLevel(), "impactLevel");
        if (command.ownerCenterId() == null || command.ownerEmployeeId() == null) {
            throw rejected("ownerCenterId and ownerEmployeeId are required");
        }
        if (command.factOccurredAt() == null) {
            throw rejected("factOccurredAt is required");
        }
        if (command.sourceFactKey().length() > 160) {
            throw rejected("sourceFactKey must not exceed 160 characters");
        }
        if (command.periodNo().length() > 32) {
            throw rejected("periodNo must not exceed 32 characters");
        }
        BigDecimal benefit = command.benefitAmount() == null ? BigDecimal.ZERO : command.benefitAmount();
        if (benefit.signum() < 0) {
            throw rejected("benefitAmount must not be negative");
        }
        long points = command.pointsDelta() == null ? 0L : command.pointsDelta();
        if (points < 0L) {
            throw rejected("pointsDelta must not be negative");
        }
        if (benefit.signum() == 0
                && points == 0L
                && (command.compGradeImpact() == null
                        || command.compGradeImpact().isBlank())) {
            throw rejected("at least one concrete reward impact is required");
        }
    }

    private Phase11Record draft(DatabaseSecurityContext actor, UUID id, CreateCommand command) {
        Instant now = Instant.now();
        ObjectNode details = mapper.createObjectNode();
        details.put("sourceFactKey", command.sourceFactKey().trim());
        details.put("contentVersion", command.contentVersion().trim());
        details.put("periodNo", command.periodNo().trim());
        details.put("benefitAmount", amount(command.benefitAmount()));
        details.put("pointsDelta", command.pointsDelta() == null ? 0L : command.pointsDelta());
        put(details, "compGradeImpact", command.compGradeImpact());
        details.put("impactLevel", command.impactLevel().trim());
        if (command.impactEffectiveDate() != null) {
            details.put("impactEffectiveDate", command.impactEffectiveDate().toString());
        }
        return new Phase11Record(
                id,
                actor.tenantId(),
                PROCESS.code(),
                numbers.next(actor.tenantId(), actor.employeeId(), PROCESS.code()),
                null,
                null,
                "S01",
                PROCESS.labelFor("S01"),
                0,
                command.subject().trim(),
                command.reason().trim(),
                normalized(command.priority(), "NORMAL"),
                normalized(command.riskLevel(), "NORMAL"),
                command.ownerCenterId(),
                command.ownerEmployeeId(),
                command.businessDate() == null ? LocalDate.now() : command.businessDate(),
                command.factOccurredAt(),
                command.factSummary().trim(),
                null,
                now,
                now,
                null,
                details);
    }

    private Phase11Record required(UUID tenantId, UUID rewardId) {
        return rewards.find(tenantId, rewardId).orElseThrow(() -> rejected("reward case not found"));
    }

    private void emit(DatabaseSecurityContext actor, Phase11Record record, String action) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("processCode", PROCESS.code());
        payload.put("recordId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("action", action);
        payload.put("nodeCode", record.currentNodeCode());
        payload.put("ownerEmployeeId", record.ownerEmployeeId().toString());
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                "P013_REWARD",
                record.id(),
                "P013_PROCESS_EVENT",
                1,
                json(payload),
                "p013:" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("P013 event serialization failed", exception);
        }
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null
                || actor.tenantId() == null
                || actor.userId() == null
                || actor.identityId() == null
                || actor.employeeId() == null
                || actor.orgId() == null
                || actor.positionId() == null) {
            throw rejected("authenticated employee context is required");
        }
    }

    private static void requireDetail(JsonNode details, String field) {
        if (details.path(field).isMissingNode()
                || details.path(field).isNull()
                || details.path(field).asText("").isBlank()) {
            throw rejected("required server fact is missing: " + field);
        }
    }

    private static BigDecimal decimal(JsonNode details, String field) {
        JsonNode value = details.path(field);
        return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw rejected("required field is missing: " + field);
        }
    }

    private static String safeAction(String value) {
        if (value == null) {
            return "INVALID";
        }
        String action = value.trim().toUpperCase(Locale.ROOT);
        return action.matches("[A-Z0-9_]{1,48}") ? action : "INVALID";
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void put(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value.trim());
        }
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P013 " + message);
    }

    public record CreateCommand(
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            LocalDate businessDate,
            Instant factOccurredAt,
            String factSummary,
            String contentVersion,
            String periodNo,
            String sourceFactKey,
            String employeeEventType,
            String impactLevel,
            LocalDate impactEffectiveDate,
            Long pointsDelta,
            BigDecimal benefitAmount,
            String compGradeImpact) {
        Phase11CreateData toCreateData() {
            return new Phase11CreateData(
                    subject,
                    reason,
                    priority,
                    riskLevel,
                    ownerCenterId,
                    ownerEmployeeId,
                    businessDate,
                    factOccurredAt,
                    factSummary,
                    contentVersion,
                    periodNo);
        }
    }

    public record ActionCommand(
            int expectedVersion,
            String summary,
            String reason,
            String decision,
            UUID financeReferenceId,
            String receiptReference) {}
}
