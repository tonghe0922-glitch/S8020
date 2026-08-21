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
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowRuntimeService {
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String PENDING = "PENDING";
    public static final String TASK_COMPLETED = "COMPLETED";
    public static final String START_NODE = "START";
    public static final String END_NODE = "END";

    private final Repository repository;
    private final WorkflowIdempotency idempotency;
    private final TransitionConditionEvaluator conditionEvaluator;
    private final ObjectMapper objectMapper;

    public WorkflowRuntimeService(
            Repository repository,
            WorkflowIdempotency idempotency,
            TransitionConditionEvaluator conditionEvaluator,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.conditionEvaluator = conditionEvaluator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Result start(StartCommand command) {
        validateStart(command);
        RuntimeVersion version = repository.findPublishedVersion(command.tenantId(), command.versionId())
                .orElseThrow(() -> new WorkflowException(
                        WorkflowException.Code.INVALID_DEFINITION,
                        "workflow instance can start only from an effective published version"));
        List<RuntimeNode> startNodes = repository.listNodesByType(
                command.tenantId(), command.versionId(), START_NODE);
        if (startNodes.size() != 1) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_DEFINITION,
                    "published workflow version must contain exactly one START node");
        }
        RuntimeNode startNode = startNodes.getFirst();
        UUID proposedInstanceId = UUID.randomUUID();
        String requestHash = hash(Map.of(
                "operation", "START",
                "versionId", command.versionId().toString(),
                "businessObjectType", command.businessObjectType(),
                "businessObjectId", command.businessObjectId() == null ? "" : command.businessObjectId().toString(),
                "businessObjectNo", value(command.businessObjectNo()),
                "title", command.title(),
                "priority", normalizedPriority(command.priority()),
                "context", canonical(command.contextSnapshot())));
        WorkflowIdempotency.Claim claim = idempotency.claim(
                command.tenantId(), command.actorId(), command.idempotencyKey(), requestHash,
                "WORKFLOW_INSTANCE", proposedInstanceId);
        if (claim.existing()) {
            Instance existing = repository.findInstance(command.tenantId(), claim.resourceId())
                    .orElseThrow(() -> WorkflowException.conflict("idempotency record points to a missing workflow instance"));
            ActionLog action = repository.findActionByRequestId(command.tenantId(), command.idempotencyKey()).orElse(null);
            Task task = repository.findCurrentTask(command.tenantId(), existing.id(), existing.currentNodeCode()).orElse(null);
            return new Result(existing, task, action, true);
        }

        Instant now = Instant.now();
        Instance instance = new Instance(
                claim.resourceId(), command.tenantId(), technicalNumber("WFI", claim.resourceId()),
                version.definitionId(), version.versionId(), version.processCode(), command.businessObjectType().trim(),
                command.businessObjectId(), blankToNull(command.businessObjectNo()), command.title().trim(), command.actorId(),
                startNode.nodeCode(), RUNNING, normalizedPriority(command.priority()), now, null, null,
                copy(command.contextSnapshot()));
        repository.insertInstance(instance, command.actorId());
        ActionLog action = new ActionLog(
                UUID.randomUUID(), command.tenantId(), instance.id(), null, "START", null, startNode.nodeCode(),
                command.actorId(), command.operatorIdentityId(), null, now, command.idempotencyKey(), requestHash);
        repository.insertAction(action, command.actorId());
        return new Result(instance, null, action, false);
    }

    @Transactional
    public Result act(ActionCommand command) {
        validateAction(command);
        Instance snapshot = repository.findInstance(command.tenantId(), command.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        String requestHash = hash(Map.of(
                "operation", "ACTION",
                "instanceId", command.instanceId().toString(),
                "taskId", command.taskId() == null ? "" : command.taskId().toString(),
                "expectedNodeCode", command.expectedNodeCode().trim(),
                "actionCode", command.actionCode().trim(),
                "reason", value(command.reason())));
        UUID proposedActionId = UUID.randomUUID();
        WorkflowIdempotency.Claim claim = idempotency.claim(
                command.tenantId(), command.actorId(), command.idempotencyKey(), requestHash,
                "WORKFLOW_ACTION", proposedActionId);
        if (claim.existing()) {
            ActionLog action = repository.findAction(command.tenantId(), claim.resourceId())
                    .orElseThrow(() -> WorkflowException.conflict("idempotency record points to a missing workflow action"));
            Instance existing = repository.findInstance(command.tenantId(), action.instanceId())
                    .orElseThrow(() -> WorkflowException.conflict("workflow action points to a missing workflow instance"));
            Task task = repository.findCurrentTask(command.tenantId(), existing.id(), existing.currentNodeCode()).orElse(null);
            return new Result(existing, task, action, true);
        }

        Instance instance = repository.lockInstance(command.tenantId(), command.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        if (!RUNNING.equals(instance.status())) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION,
                    "workflow instance is not running");
        }
        if (!command.expectedNodeCode().trim().equals(instance.currentNodeCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow current node changed; refresh before retrying the action");
        }
        RuntimeNode current = repository.findNode(command.tenantId(), instance.versionId(), instance.currentNodeCode())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                        "current workflow node is missing from bound version"));
        if (END_NODE.equalsIgnoreCase(current.nodeType())) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION,
                    "terminal workflow node does not accept actions");
        }

        Task currentTask = authorizeCurrentTask(command, instance, current);
        if (is(command.actionCode(), "WITHDRAW") && !command.actorId().equals(instance.initiatorId())) {
            throw new WorkflowException(WorkflowException.Code.FORBIDDEN,
                    "only the workflow initiator may withdraw the instance");
        }

        List<RuntimeTransition> matching = new ArrayList<>();
        for (RuntimeTransition transition : repository.listTransitions(
                command.tenantId(), instance.versionId(), current.nodeCode(), command.actionCode().trim())) {
            if (conditionEvaluator.matches(transition.conditionExpr(), instance.contextSnapshot())) {
                matching.add(transition);
            }
        }
        if (matching.isEmpty()) {
            throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION,
                    "action is not allowed from current workflow node");
        }
        if (matching.size() != 1) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                    "workflow action resolves to multiple eligible transitions");
        }
        RuntimeTransition transition = matching.getFirst();
        RuntimeNode target = repository.findNode(command.tenantId(), instance.versionId(), transition.toNodeCode())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.INVALID_DEFINITION,
                        "target workflow node is missing from bound version"));

        Instant now = Instant.now();
        if (currentTask != null) {
            int completed = repository.completeTask(
                    command.tenantId(), currentTask.id(), command.actorId(), command.actionCode().trim(), command.reason(), now);
            if (completed != 1) {
                throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                        "workflow task changed concurrently");
            }
        }

        boolean terminal = END_NODE.equalsIgnoreCase(target.nodeType());
        String instanceStatus = terminal ? terminalStatus(command.actionCode()) : RUNNING;
        Instant finishedAt = terminal ? now : null;
        int updated = repository.moveInstance(
                command.tenantId(), instance.id(), instance.currentNodeCode(), target.nodeCode(), instanceStatus,
                finishedAt, command.actorId());
        if (updated != 1) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow instance changed concurrently");
        }

        Task nextTask = null;
        if (!terminal && !START_NODE.equalsIgnoreCase(target.nodeType())) {
            UUID taskId = UUID.randomUUID();
            nextTask = new Task(
                    taskId, command.tenantId(), instance.id(), technicalNumber("WFT", taskId), target.nodeCode(),
                    target.nodeType(), null, copy(target.actorRule()), PENDING, now, null, null, null, null);
            repository.insertTask(nextTask, command.actorId());
        }

        ActionLog action = new ActionLog(
                claim.resourceId(), command.tenantId(), instance.id(), currentTask == null ? null : currentTask.id(),
                command.actionCode().trim(), current.nodeCode(), target.nodeCode(), command.actorId(),
                command.operatorIdentityId(), blankToNull(command.reason()), now, command.idempotencyKey(), requestHash);
        repository.insertAction(action, command.actorId());
        Instance moved = new Instance(
                instance.id(), instance.tenantId(), instance.instanceNo(), instance.definitionId(), instance.versionId(),
                instance.processCode(), instance.businessObjectType(), instance.businessObjectId(), instance.businessObjectNo(),
                instance.title(), instance.initiatorId(), target.nodeCode(), instanceStatus, instance.priority(),
                instance.startedAt(), finishedAt, instance.dueAt(), instance.contextSnapshot());
        return new Result(moved, nextTask, action, false);
    }

    @Transactional(readOnly = true)
    public Result get(UUID tenantId, UUID instanceId) {
        requireUuid(tenantId, "tenantId");
        requireUuid(instanceId, "instanceId");
        Instance instance = repository.findInstance(tenantId, instanceId)
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        Task task = repository.findCurrentTask(tenantId, instance.id(), instance.currentNodeCode()).orElse(null);
        return new Result(instance, task, null, false);
    }

    private Task authorizeCurrentTask(ActionCommand command, Instance instance, RuntimeNode current) {
        if (START_NODE.equalsIgnoreCase(current.nodeType())) {
            if (command.taskId() != null) {
                throw WorkflowException.invalid("START node actions must not supply taskId");
            }
            if (!command.actorId().equals(instance.initiatorId())) {
                throw new WorkflowException(WorkflowException.Code.FORBIDDEN,
                        "only the workflow initiator may act on the START node");
            }
            return null;
        }
        if (command.taskId() == null) {
            throw WorkflowException.invalid("taskId is required for workflow task actions");
        }
        Task task = repository.lockTask(command.tenantId(), command.taskId())
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
        if (!task.instanceId().equals(instance.id()) || !task.nodeCode().equals(instance.currentNodeCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow task no longer belongs to the current node");
        }
        if (!PENDING.equals(task.status())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION,
                    "workflow task is no longer pending");
        }
        if (task.assigneeId() == null) {
            throw new WorkflowException(WorkflowException.Code.NO_ELIGIBLE_APPROVER,
                    "workflow task has no server-resolved assignee");
        }
        if (!task.assigneeId().equals(command.actorId())) {
            throw new WorkflowException(WorkflowException.Code.FORBIDDEN,
                    "workflow task is assigned to a different actor");
        }
        return task;
    }

    private String terminalStatus(String actionCode) {
        if (is(actionCode, "REJECT")) return REJECTED;
        if (is(actionCode, "WITHDRAW")) return WITHDRAWN;
        return COMPLETED;
    }

    private String normalizedPriority(String priority) {
        return priority == null || priority.isBlank() ? "NORMAL" : priority.trim();
    }

    private void validateStart(StartCommand command) {
        if (command == null) throw WorkflowException.invalid("start command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireUuid(command.versionId(), "versionId");
        requireText(command.businessObjectType(), "businessObjectType");
        requireText(command.title(), "title");
        requireIdempotency(command.idempotencyKey());
    }

    private void validateAction(ActionCommand command) {
        if (command == null) throw WorkflowException.invalid("action command is required");
        requireUuid(command.tenantId(), "tenantId");
        requireUuid(command.actorId(), "actorId");
        requireUuid(command.instanceId(), "instanceId");
        requireText(command.expectedNodeCode(), "expectedNodeCode");
        requireText(command.actionCode(), "actionCode");
        if (command.actionCode().trim().length() > 32) throw WorkflowException.invalid("actionCode exceeds 32 characters");
        requireIdempotency(command.idempotencyKey());
    }

    private static void requireIdempotency(String value) {
        requireText(value, "idempotencyKey");
        if (value.length() > 128) throw WorkflowException.invalid("idempotencyKey exceeds 128 characters");
    }

    private static void requireUuid(UUID value, String field) {
        if (value == null) throw WorkflowException.invalid(field + " is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw WorkflowException.invalid(field + " is required");
    }

    private String hash(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            byte[] bytes = objectMapper.writeValueAsString(canonical(tree)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (Exception ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_ARGUMENT, "cannot hash workflow request", ex);
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

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean is(String actual, String expected) {
        return actual != null && actual.trim().equalsIgnoreCase(expected);
    }

    private static JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    public interface Repository {
        Optional<RuntimeVersion> findPublishedVersion(UUID tenantId, UUID versionId);
        List<RuntimeNode> listNodesByType(UUID tenantId, UUID versionId, String nodeType);
        Optional<RuntimeNode> findNode(UUID tenantId, UUID versionId, String nodeCode);
        List<RuntimeTransition> listTransitions(UUID tenantId, UUID versionId, String fromNodeCode, String actionCode);
        void insertInstance(Instance instance, UUID actorId);
        Optional<Instance> findInstance(UUID tenantId, UUID instanceId);
        Optional<Instance> lockInstance(UUID tenantId, UUID instanceId);
        int moveInstance(UUID tenantId, UUID instanceId, String expectedNodeCode, String targetNodeCode,
                         String status, Instant finishedAt, UUID actorId);
        void insertTask(Task task, UUID actorId);
        Optional<Task> findCurrentTask(UUID tenantId, UUID instanceId, String nodeCode);
        Optional<Task> lockTask(UUID tenantId, UUID taskId);
        int completeTask(UUID tenantId, UUID taskId, UUID actorId, String resultCode, String comment, Instant completedAt);
        void insertAction(ActionLog action, UUID actorId);
        Optional<ActionLog> findAction(UUID tenantId, UUID actionId);
        Optional<ActionLog> findActionByRequestId(UUID tenantId, String requestId);
    }

    public record StartCommand(
            UUID tenantId, UUID actorId, UUID operatorIdentityId, UUID versionId,
            String businessObjectType, UUID businessObjectId, String businessObjectNo,
            String title, String priority, JsonNode contextSnapshot, String idempotencyKey) {}

    public record ActionCommand(
            UUID tenantId, UUID actorId, UUID operatorIdentityId, UUID instanceId, UUID taskId,
            String expectedNodeCode, String actionCode, String reason, String idempotencyKey) {}

    public record RuntimeVersion(UUID definitionId, UUID versionId, String processCode, String status) {}
    public record RuntimeNode(
            String nodeCode, String nodeName, String nodeType, JsonNode actorRule, UUID slaPolicyId, int sortNo) {}
    public record RuntimeTransition(
            String fromNodeCode, String actionCode, String toNodeCode, JsonNode conditionExpr, boolean rollback) {}

    public record Instance(
            UUID id, UUID tenantId, String instanceNo, UUID definitionId, UUID versionId, String processCode,
            String businessObjectType, UUID businessObjectId, String businessObjectNo, String title,
            UUID initiatorId, String currentNodeCode, String status, String priority,
            Instant startedAt, Instant finishedAt, Instant dueAt, JsonNode contextSnapshot) {}

    public record Task(
            UUID id, UUID tenantId, UUID instanceId, String taskNo, String nodeCode, String taskType,
            UUID assigneeId, JsonNode candidateRule, String status, Instant receivedAt, Instant dueAt,
            Instant completedAt, String resultCode, String comment) {}

    public record ActionLog(
            UUID id, UUID tenantId, UUID instanceId, UUID taskId, String actionCode,
            String fromStatus, String toStatus, UUID operatorId, UUID operatorIdentityId,
            String reason, Instant occurredAt, String requestId, String snapshotHash) {}

    public record Result(Instance instance, Task task, ActionLog action, boolean replayed) {}
}
