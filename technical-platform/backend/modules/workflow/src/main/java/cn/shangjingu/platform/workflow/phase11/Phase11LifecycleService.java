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

@Service
public class Phase11LifecycleService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> SCORE_TYPES = Set.of("EMPLOYEE", "SUPERVISOR", "AUTHORITATIVE", "CALIBRATED");

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final Phase11WorkflowCoordinator workflow;
    private final Phase11Repository repository;
    private final ObjectMapper mapper;

    public Phase11LifecycleService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            Phase11WorkflowCoordinator workflow,
            Phase11Repository repository,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.repository = repository;
        this.mapper = mapper;
    }

    public Phase11Record create(
            DatabaseSecurityContext actor,
            Phase11Process process,
            String idempotencyKey,
            String requestHash,
            Phase11CreateData data) {
        requireActor(actor, process);
        validateCreate(process, data);
        return transactions.required(actor, () -> {
            if (!repository.activeEmployeeInOrg(actor.tenantId(), data.ownerCenterId(), data.ownerEmployeeId())) {
                throw rejected(process, "owner employee must be active in the owner center");
            }
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    process.table(),
                    proposedId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return required(process, actor.tenantId(), claim.resourceId());
            }

            Phase11Record draft = new Phase11Record(
                    claim.resourceId(),
                    actor.tenantId(),
                    process.code(),
                    numbers.next(actor.tenantId(), actor.employeeId(), process.code()),
                    null,
                    null,
                    "S01",
                    process.labelFor("S01"),
                    0,
                    data.subject().trim(),
                    data.reason().trim(),
                    normalized(data.priority(), "NORMAL"),
                    normalized(data.riskLevel(), "NORMAL"),
                    data.ownerCenterId(),
                    data.ownerEmployeeId(),
                    data.businessDate() == null ? LocalDate.now() : data.businessDate(),
                    data.factOccurredAt(),
                    data.factSummary().trim(),
                    null,
                    Instant.now(),
                    Instant.now(),
                    null,
                    mapper.createObjectNode());
            repository.insert(process, draft, data, actor.employeeId());
            Phase11WorkflowCoordinator.Started started = workflow.start(actor, process, draft, data, idempotencyKey);
            if (repository.bindWorkflow(
                            process,
                            actor.tenantId(),
                            draft.id(),
                            0,
                            started.workflowInstanceId(),
                            started.currentNodeCode(),
                            process.labelFor(started.currentNodeCode()),
                            actor.employeeId())
                    != 1) {
                throw rejected(process, "concurrent create transition conflict");
            }
            Phase11Record created = required(process, actor.tenantId(), draft.id());
            emit(actor, process, created, process.initialAction());
            return created;
        });
    }

    public Phase11Record act(
            DatabaseSecurityContext actor,
            Phase11Process process,
            UUID recordId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            Phase11ActionData data) {
        requireActor(actor, process);
        Objects.requireNonNull(data, process.code() + " action data is required");
        String action = safeAction(actionCode);
        return transactions.required(actor, () -> {
            Phase11Record current = required(process, actor.tenantId(), recordId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    process.table() + ".action." + action.toLowerCase(Locale.ROOT),
                    recordId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.versionNo() != data.expectedVersion()) {
                throw rejected(process, "version conflict");
            }
            Phase11Process.Step step = process.requireTransition(current.currentNodeCode(), action);
            validateActor(process, current, action, actor.employeeId());
            Phase11ActionData normalized = validateDomainAction(process, current, action, data);
            WorkflowRuntimeService.Result moved =
                    workflow.advance(actor, process, current, action, normalized.reason(), idempotencyKey);
            if (!step.targetNode().equals(moved.instance().currentNodeCode())) {
                throw rejected(process, "workflow target does not match frozen contract");
            }
            if (repository.advance(
                            process,
                            current,
                            action,
                            step.targetNode(),
                            process.labelFor(step.targetNode()),
                            normalized,
                            actor.employeeId())
                    != 1) {
                throw rejected(process, "concurrent aggregate transition conflict");
            }
            Phase11Record result = required(process, actor.tenantId(), recordId);
            emit(actor, process, result, action);
            return result;
        });
    }

    public Phase11Record submitPerformanceScore(
            DatabaseSecurityContext actor,
            UUID cycleId,
            String scoreTypeValue,
            long score1000,
            String evidenceSummary,
            int expectedVersion,
            String idempotencyKey,
            String requestHash) {
        Phase11Process process = Phase11Process.P011;
        requireActor(actor, process);
        String scoreType = safeAction(scoreTypeValue);
        if (!SCORE_TYPES.contains(scoreType)) {
            throw rejected(process, "unsupported score type");
        }
        requireScore(score1000, "score1000");
        requireText(evidenceSummary, process, "evidenceSummary");
        return transactions.required(actor, () -> {
            Phase11Record current = required(process, actor.tenantId(), cycleId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    "performance.performance_score_entry." + scoreType.toLowerCase(Locale.ROOT),
                    cycleId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.versionNo() != expectedVersion) {
                throw rejected(process, "version conflict while recording score");
            }
            validateScoreActor(current, scoreType, actor.employeeId());
            if (repository.submitPerformanceScore(
                            actor.tenantId(),
                            cycleId,
                            expectedVersion,
                            scoreType,
                            score1000,
                            evidenceSummary.trim(),
                            actor.employeeId())
                    != 1) {
                throw rejected(process, "score fact changed concurrently");
            }
            Phase11Record result = required(process, actor.tenantId(), cycleId);
            emit(actor, process, result, "SUBMIT_" + scoreType + "_SCORE");
            return result;
        });
    }

    public Optional<Phase11Record> find(DatabaseSecurityContext actor, Phase11Process process, UUID recordId) {
        requireActor(actor, process);
        return transactions.required(actor, () -> repository.find(process, actor.tenantId(), recordId));
    }

    public List<Phase11Record> list(DatabaseSecurityContext actor, Phase11Process process) {
        requireActor(actor, process);
        return transactions.required(actor, () -> repository.list(process, actor.tenantId()));
    }

    private Phase11ActionData validateDomainAction(
            Phase11Process process, Phase11Record current, String action, Phase11ActionData data) {
        if (process != Phase11Process.P011) {
            throw rejected(process, "domain checkpoint is not implemented yet");
        }
        return switch (action) {
            case "RECORD_COACHING", "COLLECT_FACTS", "EXECUTE_IMPACT", "ARCHIVE" -> {
                requireText(data.summary(), process, "summary");
                yield data;
            }
            case "SUBMIT_REVIEWS" -> {
                Phase11Repository.PerformanceScores scores =
                        repository.performanceScores(current.tenantId(), current.id());
                if (!scores.readyForCalculation()) {
                    throw rejected(process, "employee, supervisor and authoritative score facts are required");
                }
                yield data;
            }
            case "CALCULATE_SCORE" -> {
                Phase11Repository.PerformanceScores scores =
                        repository.performanceScores(current.tenantId(), current.id());
                yield copyWithScore(data, scores.calculated());
            }
            case "CALIBRATE" -> {
                Long score = repository
                        .performanceScores(current.tenantId(), current.id())
                        .calibrated();
                if (score == null) {
                    throw rejected(process, "calibrated score fact is required");
                }
                requireScore(score, "calibratedScore1000");
                yield copyWithScore(data, score);
            }
            case "SUBMIT_APPEAL_DECISION" -> {
                if (Boolean.TRUE.equals(data.appealRequested())) {
                    requireText(data.appealReason(), process, "appealReason");
                }
                yield data;
            }
            case "RESOLVE_APPEAL" -> {
                requireText(data.decision(), process, "decision");
                yield data;
            }
            case "CONFIRM_TARGETS" -> data;
            default -> data;
        };
    }

    private static Phase11ActionData copyWithScore(Phase11ActionData source, Long score) {
        return new Phase11ActionData(
                source.expectedVersion(),
                source.summary(),
                source.reason(),
                score,
                source.appealRequested(),
                source.appealReason(),
                source.decision());
    }

    private static void validateCreate(Phase11Process process, Phase11CreateData data) {
        Objects.requireNonNull(data, process.code() + " create data is required");
        requireText(data.subject(), process, "subject");
        requireText(data.reason(), process, "reason");
        requireText(data.factSummary(), process, "factSummary");
        requireText(data.contentVersion(), process, "contentVersion");
        requireText(data.periodNo(), process, "periodNo");
        if (data.ownerCenterId() == null || data.ownerEmployeeId() == null) {
            throw rejected(process, "owner center and employee are required");
        }
        if (data.factOccurredAt() == null) {
            throw rejected(process, "factOccurredAt is required");
        }
    }

    private static void validateActor(Phase11Process process, Phase11Record current, String action, UUID actorId) {
        if (process.ownerAction(action) && !actorId.equals(current.ownerEmployeeId())) {
            throw rejected(process, "only the owner employee may execute " + action);
        }
        if (!process.ownerAction(action) && actorId.equals(current.ownerEmployeeId())) {
            throw rejected(process, "self approval or self review is forbidden for " + action);
        }
    }

    private static void validateScoreActor(Phase11Record current, String scoreType, UUID actorId) {
        boolean owner = actorId.equals(current.ownerEmployeeId());
        if ("EMPLOYEE".equals(scoreType) && !owner) {
            throw rejected(Phase11Process.P011, "employee score must be submitted by the owner");
        }
        if (!"EMPLOYEE".equals(scoreType) && owner) {
            throw rejected(Phase11Process.P011, "owner cannot submit supervisor, authoritative or calibrated score");
        }
    }

    private Phase11Record required(Phase11Process process, UUID tenantId, UUID recordId) {
        return repository.find(process, tenantId, recordId).orElseThrow(() -> rejected(process, "record not found"));
    }

    private void emit(DatabaseSecurityContext actor, Phase11Process process, Phase11Record record, String action) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("processCode", process.code());
        payload.put("recordId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("action", action);
        payload.put("nodeCode", record.currentNodeCode());
        payload.put("ownerEmployeeId", record.ownerEmployeeId().toString());
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                process.code() + "_AGGREGATE",
                record.id(),
                process.code() + "_PROCESS_EVENT",
                1,
                json(payload),
                process.code().toLowerCase(Locale.ROOT) + ":" + record.id() + ":" + record.versionNo()));
    }

    private String json(ObjectNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException("PHASE-11 event serialization failed", exception);
        }
    }

    private static void requireActor(DatabaseSecurityContext actor, Phase11Process process) {
        if (actor == null
                || actor.tenantId() == null
                || actor.userId() == null
                || actor.identityId() == null
                || actor.employeeId() == null
                || actor.orgId() == null
                || actor.positionId() == null) {
            throw rejected(process, "authenticated employee context is required");
        }
    }

    private static void requireText(String value, Phase11Process process, String field) {
        if (value == null || value.isBlank()) {
            throw rejected(process, "required field is missing: " + field);
        }
    }

    private static void requireScore(long score, String field) {
        if (score < 0 || score > 1000) {
            throw rejected(Phase11Process.P011, field + " must be between 0 and 1000");
        }
    }

    private static String safeAction(String action) {
        if (action == null) {
            return "INVALID";
        }
        String value = action.trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9_]{1,48}") ? value : "INVALID";
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ProcessRejectedException rejected(Phase11Process process, String message) {
        return new ProcessRejectedException(process.code() + " " + message);
    }
}
