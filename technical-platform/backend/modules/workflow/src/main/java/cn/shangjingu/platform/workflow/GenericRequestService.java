package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Source-backed P004 domain binding over the shared workflow runtime and canonical generic_request table. */
@Service
public final class GenericRequestService {
    public static final String PROCESS_CODE = "P004";
    public static final String INITIAL_FORM_CODE = "EMP-P004-F01";
    public static final String EVENT_TYPE = "P004_GENERIC_REQUEST_EVENT";
    public static final String AGGREGATE_TYPE = "P004_GENERIC_REQUEST";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String ACT_PERMISSION = "p004.request.act";

    private static final Map<String, Set<String>> ALLOWED_ACTIONS = Map.of(
            "S02", Set.of("SUBMIT", "WITHDRAW"),
            "S03", Set.of("ACCEPT", "RETURN", "REJECT"),
            "S04", Set.of("SUBMIT_APPROVAL", "RETURN", "REJECT"),
            "S05", Set.of("APPROVE", "RETURN", "REJECT"),
            "S06", Set.of("CREATE_TASK"),
            "S07", Set.of("SUBMIT_RESULT"),
            "S08", Set.of("ACCEPT_RESULT", "RETURN"),
            "S09", Set.of("COMPLETE", "RETRY"),
            "S10", Set.of("ARCHIVE"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService taskAssignment;
    private final WorkflowFormService forms;
    private final Repository repository;
    private final ObjectMapper mapper;

    public GenericRequestService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService taskAssignment,
            WorkflowFormService forms,
            Repository repository,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.taskAssignment = taskAssignment;
        this.forms = forms;
        this.repository = repository;
        this.mapper = mapper;
    }

    public GenericRequest create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validate(command);
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "workflow.generic_request", proposedId, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());

            UUID workflowVersion = repository.latestPublishedWorkflowVersion(actor.tenantId(), PROCESS_CODE)
                    .orElseThrow(() -> new ProcessRejectedException("P004 published workflow version is not configured"));
            FormRef form = repository.latestPublishedForm(actor.tenantId(), INITIAL_FORM_CODE, PROCESS_CODE, "S02")
                    .orElseThrow(() -> new ProcessRejectedException("P004 initial published form EMP-P004-F01 is not configured"));
            List<UUID> actionCandidates = repository.permissionCandidates(
                    actor.tenantId(), ACT_PERMISSION, actor.orgId(), actor.employeeId());
            if (actionCandidates.stream().distinct().count() < 2) {
                throw new ProcessRejectedException(
                        "P004 requires at least two distinct eligible non-applicant actors for dual review and independent acceptance");
            }

            String businessNo = numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE);
            GenericRequest draft = new GenericRequest(
                    claim.resourceId(), actor.tenantId(), businessNo, null, null, "S02", label("S02"), 0,
                    command.requestType().trim(), command.subject().trim(), trimToNull(command.reason()),
                    trimToNull(command.requestedResult()), command.businessDate(), null, null,
                    actor.orgId(), actor.employeeId(), normalize(command.priority(), "NORMAL"),
                    normalize(command.riskLevel(), "NORMAL"), command.amount(), null, null, 0,
                    null, Instant.now());
            repository.insert(draft, actor.employeeId());

            ObjectNode context = mapper.createObjectNode();
            context.put("ownerEmployeeId", actor.employeeId().toString());
            context.put("ownerCenterId", actor.orgId().toString());
            context.put("requestType", draft.requestType());
            context.put("riskLevel", draft.riskLevel());
            if (draft.amount() != null) context.put("amount", draft.amount());
            context.set("actionCandidateIds", uuidArray(actionCandidates));
            context.set("recusedEmployeeIds", mapper.createArrayNode());

            WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), workflowVersion,
                    "workflow.generic_request", draft.id(), draft.businessNo(), draft.subject(), draft.priority(),
                    context, scopedKey(idempotencyKey, "start")));

            WorkflowFormService.Submission submission = forms.submit(new WorkflowFormService.SubmitForm(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    form.id(), form.versionNo(), initialFormValues(started.instance(), command), scopedKey(idempotencyKey, "form")));

            WorkflowRuntimeService.Result submitted = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    "S02", "SUBMIT", draft.reason(), scopedKey(idempotencyKey, "submit")));
            if (repository.bindWorkflowAndMove(
                    actor.tenantId(), draft.id(), 0, submitted.instance().id(), label("S03"), actor.employeeId()) != 1) {
                throw new ProcessRejectedException("P004 concurrent create transition conflict");
            }
            emit(actor.tenantId(), actor.employeeId(), draft.id(), draft.businessNo(), "S02", "SUBMIT",
                    submitted.instance().currentNodeCode(), recipients(submitted));
            GenericRequest created = required(actor.tenantId(), draft.id());
            if (!submission.id().equals(created.initialSubmissionId())) {
                throw new ProcessRejectedException("P004 initial form submission could not be recovered from the persisted instance");
            }
            return created;
        });
    }

    public GenericRequest act(
            DatabaseSecurityContext actor, UUID id, String actionCode,
            String idempotencyKey, String requestHash, ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P004 action command is required");
        String action = normalize(actionCode, "").toUpperCase(Locale.ROOT);
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "workflow.generic_request.action", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);

            GenericRequest current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            String node = runtime.instance().currentNodeCode();
            if (!Objects.equals(node, current.currentNodeCode())) {
                throw new ProcessRejectedException("P004 business projection is stale relative to workflow runtime");
            }
            Set<String> nodeActions = ALLOWED_ACTIONS.get(node);
            if (nodeActions == null || !nodeActions.contains(action)) {
                throw new ProcessRejectedException("P004 action is not allowed by the current source-backed node");
            }

            if ("S02".equals(node)) {
                if (!actor.employeeId().equals(current.ownerEmployeeId())) {
                    throw new ProcessRejectedException("P004 only the applicant may resubmit or withdraw a returned application");
                }
                if (runtime.task() != null) throw new ProcessRejectedException("P004 S02 must not expose a task assignment");
            } else {
                if (actor.employeeId().equals(current.ownerEmployeeId())) {
                    throw new ProcessRejectedException("P004 applicant cannot act on their own approval/execution task");
                }
                if (runtime.task() == null) throw new ProcessRejectedException("P004 current task is missing");
                enforceSeparation(actor, current, node);
                taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(), runtime.task().id(), actor.employeeId()));
            }

            WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(),
                    runtime.task() == null ? null : runtime.task().id(), node, action,
                    trimToNull(command.reason()), scopedKey(idempotencyKey, "workflow")));
            String targetNode = result.instance().currentNodeCode();
            Instant closedAt = "END".equals(targetNode) ? result.instance().finishedAt() : null;
            BigDecimal actualAmount = "S07".equals(node) && "SUBMIT_RESULT".equals(action)
                    ? command.actualAmount() : null;
            String resultSummary = trimToNull(command.resultSummary());
            if (repository.moveStatus(
                    actor.tenantId(), current.id(), current.versionNo(), label(targetNode),
                    actualAmount, resultSummary, closedAt, actor.employeeId()) != 1) {
                throw new ProcessRejectedException("P004 concurrent action conflict");
            }
            emit(actor.tenantId(), actor.employeeId(), current.id(), current.businessNo(), node, action,
                    targetNode, "END".equals(targetNode) ? List.of(current.ownerEmployeeId()) : recipients(result));
            return required(actor.tenantId(), current.id());
        });
    }

    public Optional<GenericRequest> find(DatabaseSecurityContext actor, UUID id) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public List<GenericRequest> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.list(actor.tenantId()));
    }

    public boolean isApplicantAction(GenericRequest request, String actionCode) {
        if (request == null) return false;
        String action = normalize(actionCode, "").toUpperCase(Locale.ROOT);
        return "S02".equals(request.currentNodeCode()) && Set.of("SUBMIT", "WITHDRAW").contains(action);
    }

    private void enforceSeparation(DatabaseSecurityContext actor, GenericRequest current, String node) {
        if ("S05".equals(node)) {
            repository.lastActorAtNodeAction(actor.tenantId(), current.workflowInstanceId(), "S04", "SUBMIT_APPROVAL")
                    .filter(actor.employeeId()::equals)
                    .ifPresent(ignored -> { throw new ProcessRejectedException("P004 S04 and S05 require distinct employees"); });
        }
        if ("S08".equals(node)) {
            repository.lastActorAtNodeAction(actor.tenantId(), current.workflowInstanceId(), "S07", "SUBMIT_RESULT")
                    .filter(actor.employeeId()::equals)
                    .ifPresent(ignored -> { throw new ProcessRejectedException("P004 acceptance must be independent from the executor"); });
        }
    }

    private GenericRequest required(UUID tenantId, UUID id) {
        return repository.find(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("P004 generic request not found"));
    }

    private List<WorkflowFormService.FieldValue> initialFormValues(
            WorkflowRuntimeService.Instance instance, CreateCommand command) {
        List<WorkflowFormService.FieldValue> values = new ArrayList<>();
        values.add(text("process_instance_no", instance.instanceNo()));
        values.add(text("process_code", PROCESS_CODE));
        values.add(text("form_code", INITIAL_FORM_CODE));
        values.add(text("request_type", command.requestType().trim()));
        values.add(text("subject", command.subject().trim()));
        addText(values, "reason", command.reason());
        addText(values, "requested_result", command.requestedResult());
        values.add(text("business_date", command.businessDate().toString()));
        values.add(text("priority", normalize(command.priority(), "NORMAL")));
        values.add(text("risk_level", normalize(command.riskLevel(), "NORMAL")));
        if (command.amount() != null) values.add(number("amount", command.amount()));
        return List.copyOf(values);
    }

    private static WorkflowFormService.FieldValue text(String code, String value) {
        return new WorkflowFormService.FieldValue(code, "TEXT", value, null, null, null, null, null, "P1", false);
    }
    private static WorkflowFormService.FieldValue number(String code, BigDecimal value) {
        return new WorkflowFormService.FieldValue(code, "NUMBER", null, value, null, null, null, null, "P1", false);
    }
    private static void addText(List<WorkflowFormService.FieldValue> values, String code, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) values.add(text(code, normalized));
    }

    private void emit(UUID tenantId, UUID actorId, UUID id, String businessNo,
                      String completedNode, String actionCode, String targetNode, List<UUID> recipients) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("requestId", id.toString());
        payload.put("businessNo", businessNo);
        payload.put("event", stageEvent(completedNode));
        payload.put("actionCode", actionCode);
        payload.put("nodeCode", targetNode);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        outbox.enqueue(new TransactionalOutboxService.Command(
                tenantId, actorId, AGGREGATE_TYPE, id, EVENT_TYPE, 1, json(payload),
                "p004:" + id + ":" + completedNode.toLowerCase(Locale.ROOT) + ":" + actionCode.toLowerCase(Locale.ROOT)));
    }

    private static String stageEvent(String node) {
        return switch (node) {
            case "S02" -> "P004.stage.02.completed";
            case "S03" -> "P004.stage.03.completed";
            case "S04" -> "P004.stage.04.completed";
            case "S05" -> "P004.stage.05.completed";
            case "S06" -> "P004.stage.06.completed";
            case "S07" -> "P004.stage.07.completed";
            case "S08" -> "P004.stage.08.completed";
            case "S09" -> "P004.stage.09.completed";
            case "S10" -> "P004.stage.10.completed";
            default -> throw new ProcessRejectedException("P004 event requested for an unknown source node: " + node);
        };
    }

    private List<UUID> recipients(WorkflowRuntimeService.Result result) {
        if (result.task() == null || result.task().candidateRule() == null) return List.of();
        JsonNode field = result.task().candidateRule().get("field");
        if (field == null || !field.isTextual()) return List.of();
        JsonNode values = result.instance().contextSnapshot() == null
                ? null : result.instance().contextSnapshot().get(field.textValue());
        if (values == null || !values.isArray()) return List.of();
        List<UUID> ids = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual()) {
                try { ids.add(UUID.fromString(value.textValue())); }
                catch (IllegalArgumentException ignored) { }
            }
        });
        return List.copyOf(ids);
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) {
            throw new ProcessRejectedException("P004 event payload cannot be serialized", ex);
        }
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode array = mapper.createArrayNode();
        ids.stream().distinct().forEach(id -> array.add(id.toString()));
        return array;
    }

    private static void validate(CreateCommand command) {
        Objects.requireNonNull(command, "P004 create command is required");
        if (command.businessDate() == null || command.requestType() == null || command.requestType().isBlank()
                || command.subject() == null || command.subject().isBlank()) {
            throw new ProcessRejectedException("P004 required request fields are missing");
        }
        if (command.amount() != null && command.amount().signum() < 0) {
            throw new ProcessRejectedException("P004 amount cannot be negative");
        }
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || actor.identityId() == null
                || actor.employeeId() == null || actor.orgId() == null || actor.positionId() == null) {
            throw new ProcessRejectedException("P004 authenticated employee context is required");
        }
    }

    private static void requireVersion(GenericRequest request, int expectedVersion) {
        if (request.versionNo() != expectedVersion) throw new ProcessRejectedException("P004 generic request version conflict");
    }

    public static String label(String node) {
        return switch (node) {
            case "S02" -> "填写申请与附件";
            case "S03" -> "前置规则校验";
            case "S04" -> "提交审批";
            case "S05" -> "动态审批与会签";
            case "S06" -> "批准后生成执行任务";
            case "S07" -> "执行人提交结果";
            case "S08" -> "独立验收";
            case "S09" -> "异常补偿";
            case "S10" -> "归档";
            case "END" -> "已关闭";
            default -> throw new ProcessRejectedException("P004 workflow returned an unknown source node: " + node);
        };
    }

    private static String scopedKey(String key, String suffix) {
        if (key == null || key.isBlank()) throw new ProcessRejectedException("P004 idempotency key is required");
        String value = key + ":" + suffix;
        if (value.length() > 128) throw new ProcessRejectedException("P004 idempotency key is too long");
        return value;
    }
    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public interface Repository {
        Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);
        Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode);
        List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId, UUID excludedEmployeeId);
        void insert(GenericRequest request, UUID actorId);
        int bindWorkflowAndMove(UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId);
        int moveStatus(UUID tenantId, UUID id, int expectedVersion, String status, BigDecimal actualAmount,
                       String resultSummary, Instant closedAt, UUID actorId);
        Optional<UUID> lastActorAtNodeAction(UUID tenantId, UUID workflowInstanceId, String nodeCode, String actionCode);
        Optional<GenericRequest> find(UUID tenantId, UUID id);
        List<GenericRequest> list(UUID tenantId);
    }

    public record FormRef(UUID id, int versionNo) {}
    public record CreateCommand(
            String requestType, String subject, String reason, String requestedResult, LocalDate businessDate,
            String priority, String riskLevel, BigDecimal amount) {}
    public record ActionCommand(int expectedVersion, String reason, String resultSummary, BigDecimal actualAmount) {}
    public record GenericRequest(
            UUID id, UUID tenantId, String businessNo, UUID workflowInstanceId, String workflowInstanceNo,
            String currentNodeCode, String status, int versionNo, String requestType, String subject,
            String reason, String requestedResult, LocalDate businessDate, BigDecimal actualAmount,
            Instant actualEndAt, UUID ownerCenterId, UUID ownerEmployeeId, String priority, String riskLevel,
            BigDecimal amount, UUID initialSubmissionId, String initialSubmissionNo, int initialFormVersion,
            String resultSummary, Instant updatedAt) {
        public GenericRequest metadataOnly() {
            return new GenericRequest(id, tenantId, businessNo, workflowInstanceId, workflowInstanceNo, currentNodeCode,
                    status, versionNo, requestType, subject, null, null, businessDate, actualAmount, actualEndAt,
                    ownerCenterId, ownerEmployeeId, priority, riskLevel, amount, initialSubmissionId,
                    initialSubmissionNo, initialFormVersion, resultSummary, updatedAt);
        }
    }
}
