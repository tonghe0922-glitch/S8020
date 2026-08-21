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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P012 promotion lifecycle. P011 score facts and appointment effects remain server authoritative. */
@Service
public final class PromotionService {
    private static final Phase11Process PROCESS = Phase11Process.P012;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> OWNER_ACTIONS = Set.of("CONFIRM_APPOINTMENT");
    private static final Set<String> REVIEW_ACTIONS = Set.of(
            "PASS_ELIGIBILITY",
            "SUBMIT_ASSESSMENT",
            "VERIFY_POSITION_BUDGET",
            "COMPLETE_REVIEW");
    private static final Set<String> APPOINT_ACTIONS = Set.of(
            "APPROVE_PROMOTION", "COMPLETE_NOTICE", "COMPLETE_VALIDATION");

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository phase11Repository;
    private final PromotionRepository promotions;
    private final ObjectMapper mapper;

    public PromotionService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository phase11Repository,
            PromotionRepository promotions,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.phase11Repository = phase11Repository;
        this.promotions = promotions;
        this.mapper = mapper;
    }

    public Phase11Record create(
            DatabaseSecurityContext actor,
            String idempotencyKey,
            String requestHash,
            CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        return transactions.required(actor, () -> {
            if (!phase11Repository.activeEmployeeInOrg(
                    actor.tenantId(), command.ownerCenterId(), command.ownerEmployeeId())) {
                throw rejected("candidate employee must be active in the owner center");
            }
            if (!promotions.activeTargetPosition(
                    actor.tenantId(), command.ownerCenterId(), command.targetPositionId())) {
                throw rejected("target position must be active in the owner center");
            }
            if (!promotions.activeCurrentAppointment(
                    actor.tenantId(), command.ownerEmployeeId(), command.currentPositionId())) {
                throw rejected("current position is not an active appointment of the candidate");
            }
            PromotionRepository.Eligibility eligibility = promotions.authoritativeEligibility(
                    actor.tenantId(),
                    command.ownerCenterId(),
                    command.ownerEmployeeId(),
                    command.sourcePerformanceCycleId());
            validatePromotionGuard(
                    eligibility.fsmState(),
                    eligibility.timeboxState(),
                    eligibility.qaState(),
                    eligibility.reviewFacetCount(),
                    eligibility.requiredScore(),
                    command.promotionThresholdScore(),
                    Boolean.TRUE.equals(command.ceoMode()),
                    command.reason());

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

            Phase11Record draft = draft(actor, claim.resourceId(), command, eligibility);
            promotions.insert(draft, command, eligibility, actor.employeeId());
            Phase11WorkflowCoordinator.Started started = workflow.start(
                    actor, PROCESS, draft, command.toCreateData(), idempotencyKey);
            if (promotions.bindWorkflow(
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
            UUID promotionId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P012 action command is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            Phase11Record current = required(actor.tenantId(), promotionId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    PROCESS.table() + ".action." + action.toLowerCase(Locale.ROOT),
                    promotionId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.versionNo() != command.expectedVersion()) {
                throw rejected("version conflict");
            }
            Phase11Process.Step step = PROCESS.requireTransition(current.currentNodeCode(), action);
            validateActor(current, action, actor.employeeId());
            validateAction(current, action, command);
            WorkflowRuntimeService.Result moved = workflow.advance(
                    actor, PROCESS, current, action, command.reason(), idempotencyKey);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected("workflow target does not match frozen contract");
            }
            UUID appointmentEffectId = null;
            if ("ACTIVATE_APPOINTMENT".equals(action)) {
                UUID targetPositionId = uuid(current.details(), "targetPositionId");
                if (!promotions.activeTargetPosition(
                        current.tenantId(), current.ownerCenterId(), targetPositionId)) {
                    throw rejected("target position is no longer active");
                }
                appointmentEffectId = promotions.activateAppointment(
                        current, command, actor.employeeId());
            }
            if (promotions.advance(
                            current,
                            action,
                            step.targetNode(),
                            PROCESS.labelFor(step.targetNode()),
                            command,
                            appointmentEffectId,
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent aggregate transition conflict");
            }
            Phase11Record result = required(actor.tenantId(), promotionId);
            emit(actor, result, action);
            return result;
        });
    }

    public Optional<Phase11Record> find(DatabaseSecurityContext actor, UUID promotionId) {
        requireActor(actor);
        return transactions.required(actor, () -> promotions.find(actor.tenantId(), promotionId));
    }

    public List<Phase11Record> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> promotions.list(actor.tenantId()));
    }

    static void validatePromotionGuard(
            String fsmState,
            String timeboxState,
            String qaState,
            int reviewFacetCount,
            long weightedReviewScore,
            long promotionThresholdScore,
            boolean ceoMode,
            String reason) {
        if (!"CLOSED".equals(fsmState)) {
            throw rejected("FSM_NOT_CLOSED");
        }
        boolean timeboxReady = "FINISHED".equals(timeboxState)
                || ("IN_PROGRESS".equals(timeboxState) && "QA_PASS".equals(qaState));
        if (!timeboxReady) {
            throw rejected("TIMEBOX_NOT_READY");
        }
        if (reviewFacetCount < 1) {
            throw rejected("NO_REVIEW_FACET");
        }
        if (weightedReviewScore < promotionThresholdScore
                && !(ceoMode && reason != null && reason.contains("[ceo_mode]"))) {
            throw rejected("REVIEW_SCORE_BELOW_THRESHOLD");
        }
    }

    private Phase11Record draft(
            DatabaseSecurityContext actor,
            UUID id,
            CreateCommand command,
            PromotionRepository.Eligibility eligibility) {
        Instant now = Instant.now();
        ObjectNode details = mapper.createObjectNode();
        details.put("sourcePerformanceCycleId", command.sourcePerformanceCycleId().toString());
        put(details, "currentPositionId", command.currentPositionId());
        details.put("targetPositionId", command.targetPositionId().toString());
        details.put("fsmState", eligibility.fsmState());
        details.put("timeboxState", eligibility.timeboxState());
        details.put("qaState", eligibility.qaState());
        details.put("reviewFacetCount", eligibility.reviewFacetCount());
        details.put("weightedReviewScore", eligibility.requiredScore());
        details.put("promotionThresholdScore", command.promotionThresholdScore());
        details.put("contentVersion", command.contentVersion());
        details.put("periodNo", command.periodNo());
        details.put("appointmentEffectiveDate", command.appointmentEffectiveDate().toString());
        details.put("ceoMode", Boolean.TRUE.equals(command.ceoMode()));
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
                command.nominationSummary().trim(),
                null,
                now,
                now,
                null,
                details);
    }

    private void validateAction(
            Phase11Record current, String action, ActionCommand command) {
        if (!OWNER_ACTIONS.contains(action)) {
            requireText(command.summary(), "summary");
        }
        if ("APPROVE_PROMOTION".equals(action)) {
            requireText(command.decision(), "decision");
        }
        if ("PASS_ELIGIBILITY".equals(action) || "APPROVE_PROMOTION".equals(action)) {
            PromotionRepository.Eligibility eligibility = promotions.authoritativeEligibility(
                    current.tenantId(),
                    current.ownerCenterId(),
                    current.ownerEmployeeId(),
                    uuid(current.details(), "sourcePerformanceCycleId"));
            validatePromotionGuard(
                    eligibility.fsmState(),
                    eligibility.timeboxState(),
                    eligibility.qaState(),
                    eligibility.reviewFacetCount(),
                    eligibility.requiredScore(),
                    current.details().path("promotionThresholdScore").asLong(),
                    current.details().path("ceoMode").asBoolean(false),
                    current.reason());
        }
        if (("COMPLETE_VALIDATION".equals(action) || "ACTIVATE_APPOINTMENT".equals(action))
                && command.appointmentEffectiveDate() == null
                && current.details().path("appointmentEffectiveDate").asText("").isBlank()) {
            throw rejected("appointmentEffectiveDate is required before activation");
        }
    }

    private static void validateActor(
            Phase11Record current, String action, UUID actorEmployeeId) {
        boolean owner = actorEmployeeId.equals(current.ownerEmployeeId());
        if (OWNER_ACTIONS.contains(action) && !owner) {
            throw rejected("only the candidate employee may confirm the appointment");
        }
        if (!OWNER_ACTIONS.contains(action) && owner) {
            throw rejected("self review, approval or activation is forbidden");
        }
        if (!OWNER_ACTIONS.contains(action)
                && !REVIEW_ACTIONS.contains(action)
                && !APPOINT_ACTIONS.contains(action)
                && !"ACTIVATE_APPOINTMENT".equals(action)) {
            throw rejected("unsupported P012 action");
        }
    }

    private static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P012 create command is required");
        requireText(command.subject(), "subject");
        requireText(command.reason(), "reason");
        requireText(command.nominationSummary(), "nominationSummary");
        requireText(command.contentVersion(), "contentVersion");
        requireText(command.periodNo(), "periodNo");
        Objects.requireNonNull(command.ownerCenterId(), "P012 ownerCenterId is required");
        Objects.requireNonNull(command.ownerEmployeeId(), "P012 ownerEmployeeId is required");
        Objects.requireNonNull(
                command.sourcePerformanceCycleId(),
                "P012 sourcePerformanceCycleId is required");
        Objects.requireNonNull(command.targetPositionId(), "P012 targetPositionId is required");
        Objects.requireNonNull(command.factOccurredAt(), "P012 factOccurredAt is required");
        Objects.requireNonNull(
                command.appointmentEffectiveDate(),
                "P012 appointmentEffectiveDate is required");
        if (command.periodNo().length() > 32) {
            throw rejected("periodNo must not exceed 32 characters");
        }
        if (command.promotionThresholdScore() < 0
                || command.promotionThresholdScore() > 1000) {
            throw rejected("promotionThresholdScore must be between 0 and 1000");
        }
    }

    private Phase11Record required(UUID tenantId, UUID promotionId) {
        return promotions
                .find(tenantId, promotionId)
                .orElseThrow(() -> rejected("promotion request not found"));
    }

    private void emit(
            DatabaseSecurityContext actor, Phase11Record record, String action) {
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
                "P012_PROMOTION",
                record.id(),
                "P012_PROCESS_EVENT",
                1,
                json(payload),
                "p012:" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("P012 event serialization failed", exception);
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

    private static UUID uuid(JsonNode details, String field) {
        String value = details.path(field).asText(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static void put(ObjectNode target, String field, UUID value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value.toString());
        }
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P012 " + message);
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
            String nominationSummary,
            String contentVersion,
            String periodNo,
            String employmentType,
            UUID sourcePerformanceCycleId,
            UUID currentPositionId,
            UUID targetPositionId,
            long promotionThresholdScore,
            LocalDate appointmentEffectiveDate,
            Boolean ceoMode) {
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
                    nominationSummary,
                    contentVersion,
                    periodNo);
        }
    }

    public record ActionCommand(
            int expectedVersion,
            String summary,
            String reason,
            String decision,
            LocalDate appointmentEffectiveDate) {}
}
