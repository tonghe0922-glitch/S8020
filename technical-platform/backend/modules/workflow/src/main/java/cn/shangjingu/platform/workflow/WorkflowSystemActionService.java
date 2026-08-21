package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only workflow action executor for deterministic background transitions.
 *
 * <p>It never impersonates a business approver: only transitions whose action code starts with
 * {@code AUTO_} are accepted, task assignment is not treated as an operator identity, and action
 * history is persisted with a null operator. API controllers must continue to use
 * {@link WorkflowRuntimeService#act(WorkflowRuntimeService.ActionCommand)}.</p>
 */
public class WorkflowSystemActionService {
    private final WorkflowRuntimeService.Repository repository;
    private final WorkflowIdempotency idempotency;
    private final TransitionConditionEvaluator conditionEvaluator;
    private final ObjectMapper objectMapper;

    public WorkflowSystemActionService(
            WorkflowRuntimeService.Repository repository,
            WorkflowIdempotency idempotency,
            TransitionConditionEvaluator conditionEvaluator,
            ObjectMapper objectMapper) {
        if (repository == null || idempotency == null || conditionEvaluator == null || objectMapper == null) {
            throw new IllegalArgumentException("workflow system-action dependencies are required");
        }
        this.repository = repository;
        this.idempotency = idempotency;
        this.conditionEvaluator = conditionEvaluator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowRuntimeService.Result act(SystemActionCommand command) {
        validate(command);
        repository.findInstance(command.tenantId(), command.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        String actionCode = command.actionCode().trim().toUpperCase(java.util.Locale.ROOT);
        String requestHash = hash(Map.of(
                "operation", "SYSTEM_ACTION",
                "instanceId", command.instanceId().toString(),
                "expectedNodeCode", command.expectedNodeCode().trim(),
                "actionCode", actionCode,
                "reason", value(command.reason())));
        UUID proposedActionId = UUID.randomUUID();
        WorkflowIdempotency.Claim claim = idempotency.claim(
                command.tenantId(), null, command.idempotencyKey(), requestHash,
                "WORKFLOW_SYSTEM_ACTION", proposedActionId);
        if (claim.existing()) {
            WorkflowRuntimeService.ActionLog action = repository.findAction(command.tenantId(), claim.resourceId())
                    .orElseThrow(() -> WorkflowException.conflict("system-action idempotency points to a missing workflow action"));
            WorkflowRuntimeService.Instance existing = repository.findInstance(command.tenantId(), action.instanceId())
                    .orElseThrow(() -> WorkflowException.conflict("system workflow action points to a missing instance"));
            WorkflowRuntimeService.Task task = repository.findCurrentTask(
                    command.tenantId(), existing.id(), existing.currentNodeCode()).orElse(null);
            return new WorkflowRuntimeService.Result(existing, task, action, true);
        }

        WorkflowRuntimeService.Instance instance = repository.lockInstance(command.tenantId(), command.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        if (!WorkflowRuntimeService.RUNNING.equals(instance.status())) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION, "workflow instance is not running");
        }
        if (!command.expectedNodeCode().trim().equals(instance.currentNodeCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow current node changed before system action");
        }
        WorkflowRuntimeService.RuntimeNode current = repository.findNode(
                        command.tenantId(), instance.versionId(), instance.currentNodeCode())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                        "current workflow node is missing from bound version"));
        if (WorkflowRuntimeService.START_NODE.equalsIgnoreCase(current.nodeType())
                || WorkflowRuntimeService.END_NODE.equalsIgnoreCase(current.nodeType())) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION,
                    "system actions require a non-terminal workflow task node");
        }

        WorkflowRuntimeService.Task visibleTask = repository.findCurrentTask(
                        command.tenantId(), instance.id(), instance.currentNodeCode())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.STALE_VERSION,
                        "current workflow task is missing"));
        WorkflowRuntimeService.Task currentTask = repository.lockTask(command.tenantId(), visibleTask.id())
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
        if (!WorkflowRuntimeService.PENDING.equals(currentTask.status())
                || !currentTask.instanceId().equals(instance.id())
                || !currentTask.nodeCode().equals(instance.currentNodeCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow task changed before system action");
        }

        List<WorkflowRuntimeService.RuntimeTransition> matching = new ArrayList<>();
        for (WorkflowRuntimeService.RuntimeTransition transition : repository.listTransitions(
                command.tenantId(), instance.versionId(), current.nodeCode(), actionCode)) {
            if (conditionEvaluator.matches(transition.conditionExpr(), instance.contextSnapshot())) {
                matching.add(transition);
            }
        }
        if (matching.isEmpty()) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION,
                    "system action is not allowed from current workflow node");
        }
        if (matching.size() != 1) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                    "system workflow action resolves to multiple eligible transitions");
        }
        WorkflowRuntimeService.RuntimeTransition transition = matching.getFirst();
        WorkflowRuntimeService.RuntimeNode target = repository.findNode(
                        command.tenantId(), instance.versionId(), transition.toNodeCode())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                        "target workflow node is missing from bound version"));

        Instant now = Instant.now();
        int completed = repository.completeTask(
                command.tenantId(), currentTask.id(), null, actionCode, command.reason(), now);
        if (completed != 1) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow task changed concurrently during system action");
        }
        boolean terminal = WorkflowRuntimeService.END_NODE.equalsIgnoreCase(target.nodeType());
        String instanceStatus = terminal ? WorkflowRuntimeService.COMPLETED : WorkflowRuntimeService.RUNNING;
        Instant finishedAt = terminal ? now : null;
        int moved = repository.moveInstance(
                command.tenantId(), instance.id(), instance.currentNodeCode(), target.nodeCode(), instanceStatus,
                finishedAt, null);
        if (moved != 1) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow instance changed concurrently during system action");
        }

        WorkflowRuntimeService.Task nextTask = null;
        if (!terminal && !WorkflowRuntimeService.START_NODE.equalsIgnoreCase(target.nodeType())) {
            UUID taskId = UUID.randomUUID();
            nextTask = new WorkflowRuntimeService.Task(
                    taskId, command.tenantId(), instance.id(), technicalNumber("WFT", taskId), target.nodeCode(),
                    target.nodeType(), null, copy(target.actorRule()), WorkflowRuntimeService.PENDING,
                    now, null, null, null, null);
            repository.insertTask(nextTask, null);
        }
        WorkflowRuntimeService.ActionLog action = new WorkflowRuntimeService.ActionLog(
                claim.resourceId(), command.tenantId(), instance.id(), currentTask.id(), actionCode,
                current.nodeCode(), target.nodeCode(), null, null, blankToNull(command.reason()), now,
                command.idempotencyKey(), requestHash);
        repository.insertAction(action, null);
        WorkflowRuntimeService.Instance result = new WorkflowRuntimeService.Instance(
                instance.id(), instance.tenantId(), instance.instanceNo(), instance.definitionId(), instance.versionId(),
                instance.processCode(), instance.businessObjectType(), instance.businessObjectId(), instance.businessObjectNo(),
                instance.title(), instance.initiatorId(), target.nodeCode(), instanceStatus, instance.priority(),
                instance.startedAt(), finishedAt, instance.dueAt(), instance.contextSnapshot());
        return new WorkflowRuntimeService.Result(result, nextTask, action, false);
    }

    private void validate(SystemActionCommand command) {
        if (command == null || command.tenantId() == null || command.instanceId() == null) {
            throw WorkflowException.invalid("system workflow tenant and instance are required");
        }
        requireText(command.expectedNodeCode(), "expectedNodeCode");
        requireText(command.actionCode(), "actionCode");
        requireText(command.idempotencyKey(), "idempotencyKey");
        String action = command.actionCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!action.startsWith("AUTO_") || action.length() > 32) {
            throw WorkflowException.invalid("system workflow action must be an AUTO_* transition");
        }
        if (command.idempotencyKey().length() > 128) {
            throw WorkflowException.invalid("idempotencyKey exceeds 128 characters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw WorkflowException.invalid(field + " is required");
    }

    private String hash(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            byte[] bytes = objectMapper.writeValueAsString(canonical(tree)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (Exception failure) {
            throw new WorkflowException(WorkflowException.Code.INVALID_ARGUMENT,
                    "cannot hash system workflow request", failure);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) return objectMapper.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> sorted.set(key, canonical(child)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return node.deepCopy();
    }

    private static String technicalNumber(String prefix, UUID id) {
        return prefix + "-" + id.toString().replace("-", "");
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static JsonNode copy(JsonNode node) { return node == null ? null : node.deepCopy(); }

    public record SystemActionCommand(
            UUID tenantId, UUID instanceId, String expectedNodeCode, String actionCode,
            String reason, String idempotencyKey) {}
}
