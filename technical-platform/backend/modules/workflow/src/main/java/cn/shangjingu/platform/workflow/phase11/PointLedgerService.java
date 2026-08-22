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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P015 immutable point-ledger workflow. The business ledger is never used as mutable workflow state. */
@Service
public class PointLedgerService {
    private static final Phase11Process PROCESS = Phase11Process.P015;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository phase11Repository;
    private final PointLedgerRepository points;
    private final ObjectMapper mapper;

    public PointLedgerService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository phase11Repository,
            PointLedgerRepository points,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.phase11Repository = phase11Repository;
        this.points = points;
        this.mapper = mapper;
    }

    public PointLedgerView create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        return transactions.required(actor, () -> {
            if (actor.employeeId().equals(command.ownerEmployeeId())) {
                throw rejected("self-created point review case is forbidden");
            }
            if (!phase11Repository.activeEmployeeInOrg(
                    actor.tenantId(), command.ownerCenterId(), command.ownerEmployeeId())) {
                throw rejected("point recipient must be active in the owner center");
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
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());

            points.reserveSource(
                    actor.tenantId(), command.sourceFactKey().trim(), claim.resourceId(), actor.employeeId());
            String businessNo = numbers.next(actor.tenantId(), actor.employeeId(), PROCESS.code());
            Phase11Record draft = draft(actor, claim.resourceId(), businessNo, command);
            Phase11WorkflowCoordinator.Started started =
                    workflow.start(actor, PROCESS, draft, command.toCreateData(), idempotencyKey);
            ObjectNode patch = createContext(command);
            if (points.mergeWorkflowContext(
                            actor.tenantId(),
                            started.workflowInstanceId(),
                            started.currentNodeCode(),
                            patch,
                            actor.employeeId())
                    != 1) {
                throw rejected("cannot persist registered point-event facts in canonical workflow");
            }
            PointLedgerView created = required(actor.tenantId(), claim.resourceId());
            emit(actor, created, PROCESS.initialAction(), null);
            return created;
        });
    }

    public PointLedgerView act(
            DatabaseSecurityContext actor,
            UUID caseId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P015 action command is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            PointLedgerView current = required(actor.tenantId(), caseId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    PROCESS.table() + ".action." + action.toLowerCase(Locale.ROOT),
                    caseId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), caseId);
            if (current.versionNo() != command.expectedVersion()) throw rejected("version conflict");
            PROCESS.requireTransition(current.currentNodeCode(), action);
            validateReviewer(current.ownerEmployeeId(), actor.employeeId());

            ObjectNode patch = validateAndApplyServerFacts(actor, current, action, command);
            if (!patch.isEmpty()
                    && points.mergeWorkflowContext(
                                    actor.tenantId(),
                                    current.workflowInstanceId(),
                                    current.currentNodeCode(),
                                    patch,
                                    actor.employeeId())
                            != 1) {
                throw rejected("workflow facts changed concurrently");
            }

            PointLedgerView refreshed = required(actor.tenantId(), caseId);
            applyImmutableEffect(actor, refreshed, action, command);
            WorkflowRuntimeService.Result moved = workflow.advance(
                    actor, PROCESS, refreshed.workflowRecord(), action, command.reason(), idempotencyKey);
            Phase11Process.Step step = PROCESS.requireTransition(refreshed.currentNodeCode(), action);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected("workflow target does not match frozen contract");
            }
            PointLedgerView result = required(actor.tenantId(), caseId);
            emit(actor, result, action, command.correctionMode());
            return result;
        });
    }

    public Optional<PointLedgerView> find(DatabaseSecurityContext actor, UUID caseId) {
        requireActor(actor);
        return transactions.required(actor, () -> points.find(actor.tenantId(), caseId));
    }

    public List<PointLedgerView> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> points.list(actor.tenantId()));
    }

    private ObjectNode validateAndApplyServerFacts(
            DatabaseSecurityContext actor, PointLedgerView current, String action, ActionCommand command) {
        JsonNode context = current.details();
        ObjectNode patch = mapper.createObjectNode();
        switch (action) {
            case "VALIDATE_SOURCE" -> {
                requireEvidence(context.path("sourceEvidence"), "sourceEvidence");
                String sourceKey = requiredText(context, "sourceFactKey");
                if (!points.sourceReservedFor(actor.tenantId(), sourceKey, current.id())) {
                    throw rejected("source-event uniqueness reservation is missing");
                }
                patch.put("sourceValidatedAt", Instant.now().toString());
            }
            case "CHECK_DUPLICATE" -> {
                requireContext(context, "sourceValidatedAt");
                if (!points.originalPostingAbsent(actor.tenantId(), current.id())) {
                    throw rejected("original point posting already exists");
                }
                patch.put("duplicateCheckedAt", Instant.now().toString());
            }
            case "MATCH_RULE_VERSION" -> {
                requireContext(context, "duplicateCheckedAt");
                PointLedgerRepository.PointRule rule = points.publishedRule(
                                actor.tenantId(),
                                requiredText(context, "ruleCode"),
                                requiredText(context, "sourceType"),
                                requiredText(context, "pointType"),
                                requiredInstant(context, "factOccurredAt"))
                        .orElseThrow(() -> rejected(
                                "no published point rule matches the registered event; calculation is fail-closed"));
                patch.put("matchedRuleId", rule.id().toString());
                patch.put("matchedRuleVersion", rule.versionCode());
                patch.put("matchedRulePoints", rule.pointsDelta());
                if (rule.reviewThresholdAbs() != null) patch.put("reviewThresholdAbs", rule.reviewThresholdAbs());
                patch.put("ruleMatchedAt", Instant.now().toString());
            }
            case "CALCULATE_POINTS" -> {
                requireContext(context, "ruleMatchedAt");
                if (!context.path("matchedRulePoints").canConvertToLong()) {
                    throw rejected("matched rule points are missing");
                }
                long calculated = context.path("matchedRulePoints").asLong();
                ObjectNode snapshot = mapper.createObjectNode();
                snapshot.put("ruleCode", requiredText(context, "ruleCode"));
                snapshot.put("ruleVersion", requiredText(context, "matchedRuleVersion"));
                snapshot.put("points", calculated);
                snapshot.put("factOccurredAt", requiredText(context, "factOccurredAt"));
                patch.put("calculatedPoints", calculated);
                patch.put("calculationSummary", "server rule calculation");
                patch.set("calculationSnapshot", snapshot);
                patch.put("pointsCalculatedAt", Instant.now().toString());
            }
            case "CLASSIFY_RISK" -> {
                requireContext(context, "pointsCalculatedAt");
                long calculated = context.path("calculatedPoints").asLong();
                long threshold = context.path("reviewThresholdAbs").asLong(Long.MAX_VALUE);
                String risk = Math.abs(calculated) >= threshold ? "HIGH" : "NORMAL";
                patch.put("riskClass", risk);
                patch.put("riskClassifiedAt", Instant.now().toString());
            }
            case "POST_OR_REVIEW" -> {
                requireContext(context, "riskClassifiedAt");
                requireText(command.summary(), "summary");
            }
            case "NOTIFY_EMPLOYEE" -> requireContext(context, "postedAt");
            case "ADJUST_OR_REVERSE" -> {
                requireContext(context, "postedAt");
                validateCorrection(command);
            }
            case "RECALCULATE_BALANCE" -> requireContext(context, "correctionReviewedAt");
            default -> throw rejected("unsupported action: " + action);
        }
        return patch;
    }

    private void applyImmutableEffect(
            DatabaseSecurityContext actor, PointLedgerView current, String action, ActionCommand command) {
        if ("POST_OR_REVIEW".equals(action)) {
            if (!points.originalPostingAbsent(actor.tenantId(), current.id()))
                throw rejected("original point posting already exists");
            points.insertPosting(current, current.details(), actor.employeeId());
            ObjectNode patch = mapper.createObjectNode();
            patch.put("postedAt", Instant.now().toString());
            if (points.mergeWorkflowContext(
                            actor.tenantId(),
                            current.workflowInstanceId(),
                            current.currentNodeCode(),
                            patch,
                            actor.employeeId())
                    != 1) {
                throw rejected("cannot persist posting fact in workflow");
            }
        } else if ("ADJUST_OR_REVERSE".equals(action)) {
            PointLedgerRepository.PointTransaction original = points.requiredOriginal(actor.tenantId(), current.id());
            String mode = normalizedMode(command.correctionMode());
            UUID correctionId = null;
            if ("REVERSAL".equals(mode)) {
                if (!points.reversalAbsent(actor.tenantId(), original.id()))
                    throw rejected("original posting was already reversed");
                correctionId = points.insertCorrection(
                        original,
                        numbers.next(actor.tenantId(), actor.employeeId(), PROCESS.code()),
                        mode,
                        Math.negateExact(original.pointsDelta()),
                        requireText(command.correctionReason(), "correctionReason"),
                        command.correctionEvidence(),
                        current.workflowInstanceId(),
                        actor.employeeId());
            } else if ("ADJUST".equals(mode)) {
                long delta = command.adjustmentDelta() == null ? 0L : command.adjustmentDelta();
                if (delta == 0L) throw rejected("adjustmentDelta must be non-zero for ADJUST");
                correctionId = points.insertCorrection(
                        original,
                        numbers.next(actor.tenantId(), actor.employeeId(), PROCESS.code()),
                        mode,
                        delta,
                        requireText(command.correctionReason(), "correctionReason"),
                        command.correctionEvidence(),
                        current.workflowInstanceId(),
                        actor.employeeId());
            }
            ObjectNode patch = mapper.createObjectNode();
            patch.put("correctionMode", mode);
            if (correctionId != null) patch.put("correctionTransactionId", correctionId.toString());
            patch.put("correctionReviewedAt", Instant.now().toString());
            if (points.mergeWorkflowContext(
                            actor.tenantId(),
                            current.workflowInstanceId(),
                            current.currentNodeCode(),
                            patch,
                            actor.employeeId())
                    != 1) {
                throw rejected("cannot persist correction review fact");
            }
        } else if ("RECALCULATE_BALANCE".equals(action)) {
            PointLedgerRepository.PointTransaction original = points.requiredOriginal(actor.tenantId(), current.id());
            long balance = points.balance(actor.tenantId(), original.ownerEmployeeId(), original.pointType());
            points.insertBalanceSnapshot(
                    actor.tenantId(),
                    original.ownerEmployeeId(),
                    original.pointType(),
                    balance,
                    current.workflowInstanceId(),
                    actor.employeeId());
        }
    }

    static void validateReviewer(UUID ownerEmployeeId, UUID actorEmployeeId) {
        if (ownerEmployeeId != null && ownerEmployeeId.equals(actorEmployeeId)) {
            throw rejected("self review, posting, adjustment and reversal are forbidden");
        }
    }

    static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P015 create command is required");
        requireText(command.subject(), "subject");
        requireText(command.reason(), "reason");
        requireText(command.factSummary(), "factSummary");
        requireText(command.sourceFactKey(), "sourceFactKey");
        requireText(command.sourceType(), "sourceType");
        requireText(command.pointType(), "pointType");
        requireText(command.ruleCode(), "ruleCode");
        requireText(command.impactLevel(), "impactLevel");
        requireEvidence(command.sourceEvidence(), "sourceEvidence");
        if (command.ownerCenterId() == null || command.ownerEmployeeId() == null) {
            throw rejected("ownerCenterId and ownerEmployeeId are required");
        }
        if (command.factOccurredAt() == null) throw rejected("factOccurredAt is required");
        if (command.sourceFactKey().trim().length() > 160)
            throw rejected("sourceFactKey must not exceed 160 characters");
    }

    private static void validateCorrection(ActionCommand command) {
        String mode = normalizedMode(command.correctionMode());
        if (!List.of("NONE", "ADJUST", "REVERSAL").contains(mode)) {
            throw rejected("correctionMode must be NONE, ADJUST or REVERSAL");
        }
        if (!"NONE".equals(mode)) {
            requireText(command.correctionReason(), "correctionReason");
            requireEvidence(command.correctionEvidence(), "correctionEvidence");
        }
    }

    private Phase11Record draft(DatabaseSecurityContext actor, UUID id, String businessNo, CreateCommand command) {
        Instant now = Instant.now();
        return new Phase11Record(
                id,
                actor.tenantId(),
                PROCESS.code(),
                businessNo,
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
                mapper.createObjectNode());
    }

    private ObjectNode createContext(CreateCommand command) {
        ObjectNode patch = mapper.createObjectNode();
        patch.put("reason", command.reason().trim());
        patch.put(
                "businessDate", (command.businessDate() == null ? LocalDate.now() : command.businessDate()).toString());
        patch.put("factOccurredAt", command.factOccurredAt().toString());
        patch.put("factSummary", command.factSummary().trim());
        patch.put("sourceFactKey", command.sourceFactKey().trim());
        patch.put("sourceType", command.sourceType().trim().toUpperCase(Locale.ROOT));
        patch.put("pointType", command.pointType().trim().toUpperCase(Locale.ROOT));
        patch.put("ruleCode", command.ruleCode().trim());
        patch.put("impactLevel", command.impactLevel().trim());
        patch.set("sourceEvidence", command.sourceEvidence().deepCopy());
        if (command.contentVersion() != null && !command.contentVersion().isBlank())
            patch.put("contentVersion", command.contentVersion().trim());
        if (command.periodNo() != null && !command.periodNo().isBlank())
            patch.put("periodNo", command.periodNo().trim());
        return patch;
    }

    private PointLedgerView required(UUID tenantId, UUID caseId) {
        return points.find(tenantId, caseId).orElseThrow(() -> rejected("point workflow case not found"));
    }

    private void emit(DatabaseSecurityContext actor, PointLedgerView record, String action, String correctionMode) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("processCode", PROCESS.code());
        payload.put("recordId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("action", action);
        payload.put("nodeCode", record.currentNodeCode());
        if (record.ownerEmployeeId() != null)
            payload.put("ownerEmployeeId", record.ownerEmployeeId().toString());
        if (correctionMode != null && !correctionMode.isBlank()) payload.put("correctionMode", correctionMode);
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                "P015_POINTS",
                record.id(),
                "P015_PROCESS_EVENT",
                1,
                json(payload),
                "p015:" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("P015 event serialization failed", exception);
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

    private static void requireContext(JsonNode context, String field) {
        if (context.path(field).isMissingNode()
                || context.path(field).isNull()
                || context.path(field).asText("").isBlank()) {
            throw rejected("required server fact is missing: " + field);
        }
    }

    private static String requiredText(JsonNode context, String field) {
        requireContext(context, field);
        return context.path(field).asText().trim();
    }

    private static Instant requiredInstant(JsonNode context, String field) {
        try {
            return Instant.parse(requiredText(context, field));
        } catch (Exception exception) {
            throw rejected("invalid timestamp server fact: " + field);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw rejected("required field is missing: " + field);
        return value.trim();
    }

    private static void requireEvidence(JsonNode value, String field) {
        if (value == null
                || value.isNull()
                || value.isMissingNode()
                || (value.isTextual() && value.asText().isBlank())
                || ((value.isObject() || value.isArray()) && value.size() == 0)) {
            throw rejected("required evidence is missing: " + field);
        }
    }

    private static String normalizedMode(String value) {
        return value == null || value.isBlank() ? "NONE" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeAction(String value) {
        if (value == null) return "INVALID";
        String action = value.trim().toUpperCase(Locale.ROOT);
        return action.matches("[A-Z0-9_]{1,48}") ? action : "INVALID";
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P015 " + message);
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
            String sourceFactKey,
            String sourceType,
            String pointType,
            String ruleCode,
            JsonNode sourceEvidence,
            String impactLevel,
            String contentVersion,
            String periodNo) {
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
            String correctionMode,
            Long adjustmentDelta,
            String correctionReason,
            JsonNode correctionEvidence) {}
}
