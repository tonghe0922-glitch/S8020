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

/** P014 discipline, responsibility, independent appeal and remediation lifecycle. */
@Service
public final class DisciplineService {
    private static final Phase11Process PROCESS = Phase11Process.P014;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository phase11Repository;
    private final DisciplineRepository discipline;
    private final ObjectMapper mapper;

    public DisciplineService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository phase11Repository,
            DisciplineRepository discipline,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.phase11Repository = phase11Repository;
        this.discipline = discipline;
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
                throw rejected("discipline subject must be active in the owner center");
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
            if (!discipline.sourceFactAvailable(actor.tenantId(), command.sourceFactKey().trim())) {
                throw rejected("source fact has already produced a discipline case");
            }
            Phase11Record draft = draft(actor, claim.resourceId(), command);
            discipline.insert(draft, command, actor.employeeId());
            Phase11WorkflowCoordinator.Started started = workflow.start(
                    actor, PROCESS, draft, command.toCreateData(), idempotencyKey);
            if (discipline.bindWorkflow(
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
            UUID caseId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P014 action command is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            Phase11Record current = required(actor.tenantId(), caseId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    PROCESS.table() + ".action." + action.toLowerCase(Locale.ROOT),
                    caseId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.versionNo() != command.expectedVersion()) {
                throw rejected("version conflict");
            }
            Phase11Process.Step step = PROCESS.requireTransition(current.currentNodeCode(), action);
            validateAction(current, actor.employeeId(), action, command);
            WorkflowRuntimeService.Result moved = workflow.advance(
                    actor, PROCESS, current, action, command.reason(), idempotencyKey);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected("workflow target does not match frozen contract");
            }
            if (discipline.advance(
                            current,
                            action,
                            step.targetNode(),
                            PROCESS.labelFor(step.targetNode()),
                            command,
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent aggregate transition conflict");
            }
            Phase11Record result = required(actor.tenantId(), caseId);
            emit(actor, result, action);
            return result;
        });
    }

    public Optional<Phase11Record> find(DatabaseSecurityContext actor, UUID caseId) {
        requireActor(actor);
        return transactions.required(actor, () -> discipline.find(actor.tenantId(), caseId));
    }

    public List<Phase11Record> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> discipline.list(actor.tenantId()));
    }

    private void validateAction(
            Phase11Record current,
            UUID actorEmployeeId,
            String action,
            ActionCommand command) {
        requireText(command.summary(), "summary");
        switch (action) {
            case "APPLY_SAFETY_MEASURE" -> {
                requireText(command.safetyMeasure(), "safetyMeasure");
                requireEvidence(command.safetyEvidence(), "safetyEvidence");
            }
            case "COMPLETE_INVESTIGATION" -> {
                validateInvestigator(current.ownerEmployeeId(), actorEmployeeId);
                requireText(command.investigationFinding(), "investigationFinding");
                requireEvidence(command.investigationEvidence(), "investigationEvidence");
            }
            case "SUBMIT_DEFENSE" -> {
                validateSubjectAction(current.ownerEmployeeId(), actorEmployeeId, "submit defense");
                requireText(command.defenseStatement(), "defenseStatement");
                requireEvidence(command.defenseEvidence(), "defenseEvidence");
            }
            case "COMPLETE_RESPONSIBILITY_REVIEW" -> {
                validateDecisionMaker(current.ownerEmployeeId(), actorEmployeeId);
                requireDetail(current.details(), "investigationCompletedAt");
                requireDetail(current.details(), "defenseSubmittedAt");
                requireText(command.responsibilityReview(), "responsibilityReview");
            }
            case "APPROVE_DECISION" -> {
                validateDecisionMaker(current.ownerEmployeeId(), actorEmployeeId);
                requireDetail(current.details(), "responsibilityReviewedAt");
                requireText(command.decision(), "decision");
            }
            case "ACKNOWLEDGE_SERVICE" -> {
                validateSubjectAction(current.ownerEmployeeId(), actorEmployeeId, "acknowledge service");
                requireDetail(current.details(), "decisionEmployeeId");
                requireEvidence(command.serviceProof(), "serviceProof");
            }
            case "EXECUTE_IMPACTS" -> {
                requireDetail(current.details(), "decisionServedAt");
                requireText(command.impactSummary(), "impactSummary");
                requireEvidence(command.impactExecutionEvidence(), "impactExecutionEvidence");
            }
            case "RESOLVE_APPEAL" -> {
                UUID decisionEmployee = uuid(current.details(), "decisionEmployeeId");
                validateAppealReviewer(current.ownerEmployeeId(), decisionEmployee, actorEmployeeId);
                requireDetail(current.details(), "impactExecutedAt");
                requireText(command.appealDecision(), "appealDecision");
                requireEvidence(command.appealDecisionEvidence(), "appealDecisionEvidence");
                validateOptionalAppealResult(command.appealResult());
            }
            case "CLOSE_CORE_CASE" -> {
                requireDetail(current.details(), "appealResolvedAt");
                requireText(command.closureSummary(), "closureSummary");
            }
            case "COMPLETE_OBSERVATION" -> {
                requireDetail(current.details(), "coreClosedAt");
                requireText(command.remediationSummary(), "remediationSummary");
                requireEvidence(command.observationEvidence(), "observationEvidence");
            }
            case "ARCHIVE" -> {
                requireDetail(current.details(), "observationCompletedAt");
                if (current.closedAt() == null) {
                    throw rejected("core case must be closed before archive");
                }
            }
            default -> throw rejected("unsupported action: " + action);
        }
    }

    static void validateInvestigator(UUID subjectEmployeeId, UUID investigatorEmployeeId) {
        if (subjectEmployeeId != null && subjectEmployeeId.equals(investigatorEmployeeId)) {
            throw rejected("subject employee cannot investigate own case");
        }
    }

    static void validateDecisionMaker(UUID subjectEmployeeId, UUID decisionEmployeeId) {
        if (subjectEmployeeId != null && subjectEmployeeId.equals(decisionEmployeeId)) {
            throw rejected("subject employee cannot decide own case");
        }
    }

    static void validateAppealReviewer(
            UUID subjectEmployeeId, UUID decisionEmployeeId, UUID reviewerEmployeeId) {
        if (subjectEmployeeId != null && subjectEmployeeId.equals(reviewerEmployeeId)) {
            throw rejected("subject employee cannot review own appeal");
        }
        if (decisionEmployeeId != null && decisionEmployeeId.equals(reviewerEmployeeId)) {
            throw rejected("original decision maker cannot be appeal reviewer");
        }
    }

    static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P014 create command is required");
        requireText(command.subject(), "subject");
        requireText(command.reason(), "reason");
        requireText(command.factSummary(), "factSummary");
        requireText(command.sourceFactKey(), "sourceFactKey");
        requireText(command.sourceType(), "sourceType");
        requireText(command.impactLevel(), "impactLevel");
        if (command.ownerCenterId() == null || command.ownerEmployeeId() == null) {
            throw rejected("ownerCenterId and ownerEmployeeId are required");
        }
        if (command.factOccurredAt() == null) {
            throw rejected("factOccurredAt is required");
        }
        if (command.sourceFactKey().trim().length() > 160) {
            throw rejected("sourceFactKey must not exceed 160 characters");
        }
        boolean hasCustomerId = command.customerId() != null && !command.customerId().isBlank();
        boolean hasCustomerName = command.customerName() != null && !command.customerName().isBlank();
        if (hasCustomerId != hasCustomerName) {
            throw rejected("customerId and customerName must be supplied together");
        }
        if (hasCustomerId && !"CUSTOMER".equalsIgnoreCase(command.sourceType().trim())) {
            throw rejected("CRM linkage is allowed only for customer-originated discipline cases");
        }
    }

    private Phase11Record draft(DatabaseSecurityContext actor, UUID id, CreateCommand command) {
        Instant now = Instant.now();
        ObjectNode details = mapper.createObjectNode();
        details.put("sourceFactKey", command.sourceFactKey().trim());
        details.put("sourceType", command.sourceType().trim().toUpperCase(Locale.ROOT));
        details.put("impactLevel", command.impactLevel().trim());
        put(details, "customerId", command.customerId());
        put(details, "customerName", command.customerName());
        put(details, "contentVersion", command.contentVersion());
        put(details, "periodNo", command.periodNo());
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

    private Phase11Record required(UUID tenantId, UUID caseId) {
        return discipline.find(tenantId, caseId)
                .orElseThrow(() -> rejected("discipline case not found"));
    }

    private void emit(DatabaseSecurityContext actor, Phase11Record record, String action) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("processCode", PROCESS.code());
        payload.put("recordId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("action", action);
        payload.put("nodeCode", record.currentNodeCode());
        payload.put("status", record.status());
        payload.put("subjectEmployeeId", record.ownerEmployeeId().toString());
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                "P014_DISCIPLINE",
                record.id(),
                "P014_PROCESS_EVENT",
                1,
                json(payload),
                "p014:" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("P014 event serialization failed", exception);
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

    private static void validateSubjectAction(UUID subjectEmployeeId, UUID actorEmployeeId, String verb) {
        if (subjectEmployeeId == null || !subjectEmployeeId.equals(actorEmployeeId)) {
            throw rejected("only the subject employee may " + verb);
        }
    }

    private static void validateOptionalAppealResult(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String result = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("UPHOLD", "MODIFY", "OVERTURN").contains(result)) {
            throw rejected("appealResult must be UPHOLD, MODIFY or OVERTURN when supplied");
        }
    }

    private static void requireDetail(JsonNode details, String field) {
        JsonNode value = details.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText("").isBlank()) {
            throw rejected("required server fact is missing: " + field);
        }
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

    private static UUID uuid(JsonNode details, String field) {
        String value = details.path(field).asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw rejected("invalid server UUID fact: " + field);
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

    private static void put(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value.trim());
        }
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P014 " + message);
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
            String customerId,
            String customerName,
            String impactLevel,
            LocalDate impactEffectiveDate,
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
            String safetyMeasure,
            JsonNode safetyEvidence,
            String investigationFinding,
            JsonNode investigationEvidence,
            String defenseStatement,
            JsonNode defenseEvidence,
            String responsibilityReview,
            String decision,
            JsonNode serviceProof,
            String impactSummary,
            JsonNode impactExecutionEvidence,
            String appealResult,
            String appealDecision,
            JsonNode appealDecisionEvidence,
            String closureSummary,
            String remediationSummary,
            JsonNode observationEvidence) {}
}
