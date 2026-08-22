package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.welfare.CareCaseService;
import cn.shangjingu.platform.welfare.JdbcCareCaseRepository;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P016 PHASE-11 adapter over the pre-existing welfare.care_case kernel. */
@Service
public class Phase11CareCaseService {
    private static final Phase11Process PROCESS = Phase11Process.P016;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> REQUIRED_CLOSE_FACTS = Set.of(
            "ELIGIBILITY_VERIFIED",
            "PRIVACY_AUTHORIZED",
            "CARE_APPROVED",
            "BENEFIT_EXECUTED",
            "RECEIPT_CONFIRMED",
            "RECONCILED");

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final CareCaseService careCases;
    private final JdbcCareCaseRepository repository;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository phase11Repository;
    private final TransactionalOutboxService outbox;
    private final ObjectMapper mapper;

    public Phase11CareCaseService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            CareCaseService careCases,
            JdbcCareCaseRepository repository,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository phase11Repository,
            TransactionalOutboxService outbox,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.careCases = careCases;
        this.repository = repository;
        this.workflow = workflow;
        this.phase11Repository = phase11Repository;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    public Phase11CareCaseView create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        return transactions.required(actor, () -> {
            if (!phase11Repository.activeEmployeeInOrg(
                    actor.tenantId(), command.ownerCenterId(), command.ownerEmployeeId())) {
                throw rejected("care recipient must be active in the owner center");
            }
            LocalDate businessDate = command.businessDate() == null ? LocalDate.now() : command.businessDate();
            CareCaseService.CareCase base = careCases.create(
                    actor,
                    idempotencyKey,
                    requestHash,
                    new CareCaseService.CreateCommand(
                            "PORTAL",
                            businessDate,
                            command.subject().trim(),
                            command.reason().trim(),
                            normalized(command.priority(), "NORMAL"),
                            normalized(command.riskLevel(), "NORMAL"),
                            command.ownerCenterId(),
                            command.ownerDepartmentId(),
                            command.ownerEmployeeId(),
                            command.benefitAmount(),
                            trimToNull(command.budgetItemId()),
                            command.costCenterId().trim(),
                            command.currency().trim(),
                            "P016_CARE",
                            command.factOccurredAt(),
                            command.factSummary().trim(),
                            command.impactEffectiveDate(),
                            command.impactLevel().trim(),
                            null));

            JdbcCareCaseRepository.CanonicalCase existing = repository
                    .findCanonical(actor.tenantId(), base.id())
                    .orElseThrow(() -> rejected("canonical care case was not persisted"));
            if (existing.workflowInstanceId() != null) {
                return toView(existing);
            }

            Phase11CreateData createData = new Phase11CreateData(
                    existing.subject(),
                    existing.reason(),
                    existing.priority(),
                    existing.riskLevel(),
                    existing.ownerCenterId(),
                    existing.ownerEmployeeId(),
                    existing.businessDate(),
                    existing.factOccurredAt(),
                    existing.factSummary(),
                    trimToNull(command.contentVersion()),
                    trimToNull(command.periodNo()));
            Phase11Record draft = toWorkflowRecord(existing, "S01");
            Phase11WorkflowCoordinator.Started started =
                    workflow.start(actor, PROCESS, draft, createData, idempotencyKey);
            if (repository.bindWorkflow(
                            actor.tenantId(),
                            existing.id(),
                            existing.versionNo(),
                            started.workflowInstanceId(),
                            started.currentNodeCode(),
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent care-case workflow binding conflict");
            }
            Phase11CareCaseView created = required(actor.tenantId(), existing.id());
            emit(actor, created, PROCESS.initialAction());
            return created;
        });
    }

    public Phase11CareCaseView act(
            DatabaseSecurityContext actor,
            UUID caseId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P016 action command is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            Phase11CareCaseView current = required(actor.tenantId(), caseId);
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
            Phase11Process.Step step = PROCESS.requireTransition(current.currentNodeCode(), action);
            validateActionActor(current, action, actor.employeeId());
            String summary = requireText(command.summary(), "summary");
            String factType = factType(action);
            String evidenceReference = trimToNull(command.evidenceReference());
            if (requiresEvidenceReference(action) && evidenceReference == null) {
                throw rejected("evidenceReference is required for " + action);
            }
            if ("ARCHIVE".equals(action)) {
                requireCloseFacts(current);
            }

            if ("EXECUTE_BENEFIT".equals(action)) {
                careCases.executeBenefit(actor, caseId, command.invoiceEvidence());
            } else if ("RECONCILE".equals(action)) {
                careCases.reconcileBenefit(actor, caseId);
            }

            if (repository.insertFact(
                            actor.tenantId(),
                            caseId,
                            factType,
                            summary,
                            evidenceReference,
                            actor.employeeId(),
                            actor.userId())
                    != 1) {
                throw rejected("business fact already exists for action " + action);
            }

            Phase11Record workflowRecord = toWorkflowRecord(current);
            WorkflowRuntimeService.Result moved = workflow.advance(
                    actor, PROCESS, workflowRecord, action, trimToNull(command.reason()), idempotencyKey);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected("workflow target does not match frozen contract");
            }
            boolean close = "END".equals(step.targetNode());
            if (repository.advanceCanonical(
                            actor.tenantId(),
                            caseId,
                            current.versionNo(),
                            current.currentNodeCode(),
                            step.targetNode(),
                            summary,
                            "EXECUTE_BENEFIT".equals(action),
                            close,
                            actor.employeeId())
                    != 1) {
                throw rejected("concurrent care-case aggregate transition conflict");
            }
            Phase11CareCaseView result = required(actor.tenantId(), caseId);
            emit(actor, result, action);
            return result;
        });
    }

    public Optional<Phase11CareCaseView> find(DatabaseSecurityContext actor, UUID caseId) {
        requireActor(actor);
        return transactions.required(
                actor, () -> repository.findCanonical(actor.tenantId(), caseId).map(this::toView));
    }

    public List<Phase11CareCaseView> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.listCanonical(actor.tenantId()).stream()
                .map(this::toView)
                .toList());
    }

    private void requireCloseFacts(Phase11CareCaseView current) {
        Set<String> present = current.facts().stream()
                .map(Phase11CareCaseView.FactView::factType)
                .collect(java.util.stream.Collectors.toSet());
        if (!present.containsAll(REQUIRED_CLOSE_FACTS)) {
            throw rejected(
                    "care case cannot archive before eligibility, privacy, approval, execution, receipt and reconciliation facts exist");
        }
    }

    private Phase11CareCaseView required(UUID tenantId, UUID caseId) {
        return repository
                .findCanonical(tenantId, caseId)
                .map(this::toView)
                .orElseThrow(() -> rejected("care workflow case not found"));
    }

    private Phase11CareCaseView toView(JdbcCareCaseRepository.CanonicalCase c) {
        List<Phase11CareCaseView.FactView> facts = repository.facts(c.tenantId(), c.id()).stream()
                .map(f -> new Phase11CareCaseView.FactView(
                        f.id(), f.factType(), f.summary(), f.evidenceReference(), f.actorEmployeeId(), f.occurredAt()))
                .toList();
        return new Phase11CareCaseView(
                c.id(),
                c.tenantId(),
                PROCESS.code(),
                c.businessNo(),
                c.workflowInstanceId(),
                c.currentNodeCode(),
                c.status(),
                c.versionNo(),
                c.subject(),
                c.reason(),
                c.priority(),
                c.riskLevel(),
                c.ownerCenterId(),
                c.ownerDepartmentId(),
                c.ownerEmployeeId(),
                c.businessDate(),
                c.benefitAmount(),
                c.budgetItemId(),
                c.costCenterId(),
                c.currency(),
                c.factOccurredAt(),
                c.factSummary(),
                c.impactLevel(),
                c.resultSummary(),
                c.actualStartAt(),
                c.actualEndAt(),
                c.closedAt(),
                c.createdAt(),
                c.updatedAt(),
                facts);
    }

    private Phase11Record toWorkflowRecord(Phase11CareCaseView c) {
        return new Phase11Record(
                c.id(),
                c.tenantId(),
                PROCESS.code(),
                c.businessNo(),
                c.workflowInstanceId(),
                null,
                c.currentNodeCode(),
                c.status(),
                c.versionNo(),
                c.subject(),
                c.reason(),
                c.priority(),
                c.riskLevel(),
                c.ownerCenterId(),
                c.ownerEmployeeId(),
                c.businessDate(),
                c.factOccurredAt(),
                c.factSummary(),
                c.resultSummary(),
                c.createdAt(),
                c.updatedAt(),
                c.closedAt(),
                mapper.createObjectNode());
    }

    private Phase11Record toWorkflowRecord(JdbcCareCaseRepository.CanonicalCase c, String nodeCode) {
        return new Phase11Record(
                c.id(),
                c.tenantId(),
                PROCESS.code(),
                c.businessNo(),
                null,
                null,
                nodeCode,
                PROCESS.labelFor(nodeCode),
                c.versionNo(),
                c.subject(),
                c.reason(),
                c.priority(),
                c.riskLevel(),
                c.ownerCenterId(),
                c.ownerEmployeeId(),
                c.businessDate(),
                c.factOccurredAt(),
                c.factSummary(),
                c.resultSummary(),
                c.createdAt(),
                c.updatedAt(),
                c.closedAt(),
                mapper.createObjectNode());
    }

    private void emit(DatabaseSecurityContext actor, Phase11CareCaseView record, String action) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("processCode", PROCESS.code());
        payload.put("recordId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("action", action);
        if (record.currentNodeCode() != null) payload.put("nodeCode", record.currentNodeCode());
        if (record.ownerEmployeeId() != null)
            payload.put("ownerEmployeeId", record.ownerEmployeeId().toString());
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                "P016_CARE",
                record.id(),
                "P016_PROCESS_EVENT",
                1,
                json(payload),
                "p016:" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("P016 event serialization failed", exception);
        }
    }

    static void validateActionActor(Phase11CareCaseView current, String action, UUID actorEmployeeId) {
        if (PROCESS.ownerAction(action)
                && (current.ownerEmployeeId() == null
                        || !current.ownerEmployeeId().equals(actorEmployeeId))) {
            throw rejected("only the care recipient may perform " + action);
        }
    }

    private static boolean requiresEvidenceReference(String action) {
        return Set.of("AUTHORIZE_PRIVACY", "EXECUTE_BENEFIT", "CONFIRM_RECEIPT", "RECONCILE")
                .contains(action);
    }

    private static String factType(String action) {
        return switch (action) {
            case "VERIFY_ELIGIBILITY" -> "ELIGIBILITY_VERIFIED";
            case "AUTHORIZE_PRIVACY" -> "PRIVACY_AUTHORIZED";
            case "APPROVE_CARE" -> "CARE_APPROVED";
            case "EXECUTE_BENEFIT" -> "BENEFIT_EXECUTED";
            case "CONFIRM_RECEIPT" -> "RECEIPT_CONFIRMED";
            case "RECONCILE" -> "RECONCILED";
            case "ARCHIVE" -> "ARCHIVED";
            default -> throw rejected("unsupported action " + action);
        };
    }

    static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P016 create command is required");
        requireText(command.subject(), "subject");
        requireText(command.reason(), "reason");
        requireText(command.factSummary(), "factSummary");
        requireText(command.costCenterId(), "costCenterId");
        requireText(command.currency(), "currency");
        requireText(command.impactLevel(), "impactLevel");
        if (command.ownerCenterId() == null || command.ownerEmployeeId() == null) {
            throw rejected("ownerCenterId and ownerEmployeeId are required");
        }
        if (command.factOccurredAt() == null) throw rejected("factOccurredAt is required");
        if (command.benefitAmount() != null && command.benefitAmount().signum() < 0) {
            throw rejected("benefitAmount must be non-negative");
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw rejected("required field is missing: " + field);
        return value.trim();
    }

    private static String safeAction(String value) {
        if (value == null) return "INVALID";
        String action = value.trim().toUpperCase(Locale.ROOT);
        return action.matches("[A-Z0-9_]{1,48}") ? action : "INVALID";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P016 " + message);
    }

    public record CreateCommand(
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerDepartmentId,
            UUID ownerEmployeeId,
            LocalDate businessDate,
            BigDecimal benefitAmount,
            String budgetItemId,
            String costCenterId,
            String currency,
            Instant factOccurredAt,
            String factSummary,
            LocalDate impactEffectiveDate,
            String impactLevel,
            String contentVersion,
            String periodNo) {}

    public record ActionCommand(
            int expectedVersion,
            String summary,
            String reason,
            String evidenceReference,
            CareCaseService.InvoiceEvidence invoiceEvidence) {}
}
