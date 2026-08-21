package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowSlaService {
    public static final String SLA_STARTED = "SLA_STARTED";
    public static final String SLA_PAUSED = "SLA_PAUSED";
    public static final String SLA_RESUMED = "SLA_RESUMED";
    public static final String SLA_REMINDER_DUE = "SLA_REMINDER_DUE";
    public static final String SLA_ESCALATION_DUE = "SLA_ESCALATION_DUE";
    public static final String SLA_REMINDER_SENT = "SLA_REMINDER_SENT";
    public static final String SLA_ESCALATION_SENT = "SLA_ESCALATION_SENT";

    private final Repository repository;
    private final ObjectMapper mapper;
    private final List<WorkingCalendarCapability> calendars;
    private final List<RuleEvaluatorCapability> evaluators;
    private final List<NotificationCapability> notifications;
    private final List<CriticalAuditCapability> criticalAudits;

    public WorkflowSlaService(
            Repository repository,
            ObjectMapper mapper,
            List<WorkingCalendarCapability> calendars,
            List<RuleEvaluatorCapability> evaluators,
            List<NotificationCapability> notifications,
            List<CriticalAuditCapability> criticalAudits) {
        this.repository = repository;
        this.mapper = mapper;
        this.calendars = List.copyOf(calendars);
        this.evaluators = List.copyOf(evaluators);
        this.notifications = List.copyOf(notifications);
        this.criticalAudits = List.copyOf(criticalAudits);
    }

    @Transactional
    public SlaState start(UUID tenantId, UUID taskId, UUID actorId, String requestId, Instant now) {
        validateIdentity(tenantId, taskId, actorId, requestId, now);
        Optional<ActionEvidence> replay = repository.findActionByRequestId(tenantId, requestId);
        if (replay.isPresent()) return stateFromEvidence(taskId, replay.get());

        Locked locked = lockCurrent(tenantId, taskId);
        Binding binding = binding(tenantId, locked.instance(), locked.task());
        WorkingCalendarCapability calendar = calendar(binding.policy().calendarId());
        Instant dueAt = calendar.addWorkingMinutes(
                tenantId, binding.policy().calendarId(), locked.task().receivedAt(), binding.policy().durationMinutes());
        if (dueAt == null || dueAt.isBefore(locked.task().receivedAt())) {
            throw invalidDefinition("working calendar returned invalid due time");
        }
        updateDue(tenantId, locked, dueAt, actorId);

        ObjectNode evidence = evidenceBase(binding, dueAt, dueAt)
                .put("startedAt", now.toString());
        emit(tenantId, locked, SLA_STARTED, actorId, requestId, now, evidence);
        return new SlaState(taskId, dueAt, dueAt, false, null);
    }

    @Transactional
    public SlaState pause(UUID tenantId, UUID taskId, UUID actorId, String requestId, Instant now) {
        validateIdentity(tenantId, taskId, actorId, requestId, now);
        Optional<ActionEvidence> replay = repository.findActionByRequestId(tenantId, requestId);
        if (replay.isPresent()) return stateFromEvidence(taskId, replay.get());

        Locked locked = lockCurrent(tenantId, taskId);
        if (locked.task().dueAt() == null) throw WorkflowException.invalid("SLA has not been started for task");
        Binding binding = binding(tenantId, locked.instance(), locked.task());
        ActionEvidence start = repository.findFirstAction(tenantId, taskId, SLA_STARTED)
                .orElseThrow(() -> WorkflowException.invalid("SLA start evidence is missing"));
        Instant originalDueAt = evidenceInstant(start, "originalDueAt");
        Optional<ActionEvidence> latest = repository.findLatestLifecycleAction(tenantId, taskId);
        if (latest.isPresent() && SLA_PAUSED.equals(latest.get().actionCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "SLA is already paused");
        }

        ObjectNode evidence = evidenceBase(binding, originalDueAt, locked.task().dueAt())
                .put("pausedAt", now.toString());
        emit(tenantId, locked, SLA_PAUSED, actorId, requestId, now, evidence);
        return new SlaState(taskId, originalDueAt, locked.task().dueAt(), true, now);
    }

    @Transactional
    public SlaState resume(UUID tenantId, UUID taskId, UUID actorId, String requestId, Instant now) {
        validateIdentity(tenantId, taskId, actorId, requestId, now);
        Optional<ActionEvidence> replay = repository.findActionByRequestId(tenantId, requestId);
        if (replay.isPresent()) return stateFromEvidence(taskId, replay.get());

        Locked locked = lockCurrent(tenantId, taskId);
        if (locked.task().dueAt() == null) throw WorkflowException.invalid("SLA has not been started for task");
        Binding binding = binding(tenantId, locked.instance(), locked.task());
        ActionEvidence pause = repository.findLatestLifecycleAction(tenantId, taskId)
                .filter(action -> SLA_PAUSED.equals(action.actionCode()))
                .orElseThrow(() -> new WorkflowException(WorkflowException.Code.STALE_VERSION, "SLA is not paused"));
        Instant pausedAt = evidenceInstant(pause, "pausedAt");
        Instant originalDueAt = evidenceInstant(pause, "originalDueAt");
        if (now.isBefore(pausedAt)) throw WorkflowException.invalid("resume time precedes pause time");

        WorkingCalendarCapability calendar = calendar(binding.policy().calendarId());
        long pausedWorkingMinutes = calendar.workingMinutesBetween(
                tenantId, binding.policy().calendarId(), pausedAt, now);
        if (pausedWorkingMinutes < 0) throw invalidDefinition("working calendar returned negative paused duration");
        Instant resumedDueAt = calendar.addWorkingMinutes(
                tenantId, binding.policy().calendarId(), locked.task().dueAt(), pausedWorkingMinutes);
        if (resumedDueAt == null || resumedDueAt.isBefore(locked.task().dueAt())) {
            throw invalidDefinition("working calendar returned invalid resumed due time");
        }
        updateDue(tenantId, locked, resumedDueAt, actorId);

        ObjectNode evidence = evidenceBase(binding, originalDueAt, resumedDueAt)
                .put("pausedAt", pausedAt.toString())
                .put("resumedAt", now.toString())
                .put("previousEffectiveDueAt", locked.task().dueAt().toString())
                .put("pausedWorkingMinutes", pausedWorkingMinutes);
        emit(tenantId, locked, SLA_RESUMED, actorId, requestId, now, evidence);
        return new SlaState(taskId, originalDueAt, resumedDueAt, false, null);
    }

    @Transactional
    public List<PendingNotification> evaluate(UUID tenantId, UUID taskId, UUID actorId, Instant now) {
        if (tenantId == null || taskId == null || actorId == null || now == null) {
            throw WorkflowException.invalid("tenantId, taskId, actorId and evaluation time are required");
        }
        Locked locked = lockCurrent(tenantId, taskId);
        if (locked.task().dueAt() == null) throw WorkflowException.invalid("SLA has not been started for task");
        Optional<ActionEvidence> latest = repository.findLatestLifecycleAction(tenantId, taskId);
        if (latest.isPresent() && SLA_PAUSED.equals(latest.get().actionCode())) return List.of();

        Binding binding = binding(tenantId, locked.instance(), locked.task());
        if (binding.policy().remindRules() == null && binding.policy().escalationRules() == null) return List.of();
        RuleEvaluatorCapability evaluator = evaluator(binding.policy());
        List<Decision> decisions = evaluator.evaluate(
                tenantId, binding.policy(), locked.instance(), locked.task(), now);
        if (decisions == null) throw invalidDefinition("SLA rule evaluator returned null decisions");

        List<PendingNotification> pending = new ArrayList<>();
        for (Decision decision : decisions) {
            validateDecision(decision);
            String actionCode = decision.kind() == DecisionKind.REMINDER ? SLA_REMINDER_DUE : SLA_ESCALATION_DUE;
            String requestId = "sla:" + taskId + ":" + decision.kind().name().toLowerCase() + ":" + decision.decisionKey();
            if (repository.findActionByRequestId(tenantId, requestId).isPresent()) continue;
            ActionEvidence start = repository.findFirstAction(tenantId, taskId, SLA_STARTED)
                    .orElseThrow(() -> WorkflowException.invalid("SLA start evidence is missing"));
            Instant originalDueAt = evidenceInstant(start, "originalDueAt");
            ObjectNode evidence = evidenceBase(binding, originalDueAt, locked.task().dueAt())
                    .put("decisionKey", decision.decisionKey())
                    .put("decisionKind", decision.kind().name())
                    .put("evaluatedAt", now.toString());
            evidence.putPOJO("recipientIds", decision.recipientIds());
            emit(tenantId, locked, actionCode, actorId, requestId, now, evidence);
            pending.add(new PendingNotification(
                    tenantId, taskId, locked.instance().id(), decision.kind(), decision.decisionKey(),
                    decision.recipientIds(), requestId, originalDueAt, locked.task().dueAt()));
        }
        return List.copyOf(pending);
    }

    @Transactional
    public DeliveryResult dispatch(PendingNotification notification, UUID actorId, Instant now) {
        if (notification == null || actorId == null || now == null) {
            throw WorkflowException.invalid("notification, actorId and dispatch time are required");
        }
        String sentRequestId = notification.decisionRequestId() + ":sent";
        Optional<ActionEvidence> replay = repository.findActionByRequestId(notification.tenantId(), sentRequestId);
        if (replay.isPresent()) {
            JsonNode evidence = parseEvidence(replay.get());
            return new DeliveryResult(notification.decisionRequestId(), text(evidence, "providerReceipt"), true);
        }
        ActionEvidence due = repository.findActionByRequestId(notification.tenantId(), notification.decisionRequestId())
                .orElseThrow(() -> WorkflowException.invalid("SLA notification decision evidence is missing"));
        if (!due.taskId().equals(notification.taskId())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "SLA notification task binding changed");
        }
        Locked locked = lockCurrent(notification.tenantId(), notification.taskId());
        NotificationCapability capability = notification(notification.kind());
        DeliveryReceipt receipt = capability.deliver(notification);
        if (receipt == null || blank(receipt.providerReceipt())) {
            throw invalidDefinition("notification provider did not return delivery evidence");
        }
        String actionCode = notification.kind() == DecisionKind.REMINDER ? SLA_REMINDER_SENT : SLA_ESCALATION_SENT;
        ObjectNode evidence = mapper.createObjectNode()
                .put("decisionRequestId", notification.decisionRequestId())
                .put("decisionKey", notification.decisionKey())
                .put("decisionKind", notification.kind().name())
                .put("providerReceipt", receipt.providerReceipt())
                .put("deliveredAt", now.toString());
        evidence.putPOJO("recipientIds", notification.recipientIds());
        emit(notification.tenantId(), locked, actionCode, actorId, sentRequestId, now, evidence);
        return new DeliveryResult(notification.decisionRequestId(), receipt.providerReceipt(), false);
    }

    private Locked lockCurrent(UUID tenantId, UUID taskId) {
        SlaTask snapshot = repository.findTask(tenantId, taskId)
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
        SlaInstance instance = repository.lockInstance(tenantId, snapshot.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        SlaTask task = repository.lockTask(tenantId, taskId)
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
        if (!task.instanceId().equals(instance.id()) || !task.nodeCode().equals(instance.currentNodeCode())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "SLA task is not the current workflow node");
        }
        if (!WorkflowRuntimeService.RUNNING.equals(instance.status()) || !WorkflowRuntimeService.PENDING.equals(task.status())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "SLA task or instance is no longer active");
        }
        return new Locked(instance, task);
    }

    private Binding binding(UUID tenantId, SlaInstance instance, SlaTask task) {
        UUID policyId = repository.findNodeSlaPolicyId(tenantId, instance.versionId(), task.nodeCode())
                .orElseThrow(() -> invalidDefinition("current workflow node has no SLA policy"));
        SlaPolicy policy = repository.findPolicy(tenantId, policyId)
                .orElseThrow(() -> invalidDefinition("workflow SLA policy does not exist"));
        if (policy.durationMinutes() <= 0) throw invalidDefinition("SLA duration must be positive");
        if (policy.processCode() != null && !policy.processCode().equals(instance.processCode())) {
            throw invalidDefinition("SLA policy process binding does not match workflow instance");
        }
        if (policy.nodeCode() != null && !policy.nodeCode().equals(task.nodeCode())) {
            throw invalidDefinition("SLA policy node binding does not match workflow task");
        }
        return new Binding(policyId, policy);
    }

    private void updateDue(UUID tenantId, Locked locked, Instant dueAt, UUID actorId) {
        if (repository.updateTaskDueAt(tenantId, locked.task().id(), dueAt, actorId) != 1) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "workflow task changed while updating SLA due time");
        }
        if (repository.updateInstanceDueAt(tenantId, locked.instance().id(), dueAt, actorId) != 1) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "workflow instance changed while updating SLA due time");
        }
    }

    private WorkingCalendarCapability calendar(UUID calendarId) {
        List<WorkingCalendarCapability> matches = calendars.stream().filter(capability -> capability.supports(calendarId)).toList();
        if (matches.size() != 1) throw invalidDefinition("working calendar capability is unavailable or ambiguous");
        return matches.getFirst();
    }

    private RuleEvaluatorCapability evaluator(SlaPolicy policy) {
        List<RuleEvaluatorCapability> matches = evaluators.stream().filter(capability -> capability.supports(policy)).toList();
        if (matches.size() != 1) throw invalidDefinition("SLA rule evaluator capability is unavailable or ambiguous");
        return matches.getFirst();
    }

    private NotificationCapability notification(DecisionKind kind) {
        List<NotificationCapability> matches = notifications.stream().filter(capability -> capability.supports(kind)).toList();
        if (matches.size() != 1) throw invalidDefinition("notification capability is unavailable or ambiguous");
        return matches.getFirst();
    }

    private void emit(
            UUID tenantId, Locked locked, String actionCode, UUID actorId,
            String requestId, Instant occurredAt, JsonNode evidence) {
        String reason = json(evidence);
        String snapshotHash = sha256(reason);
        CriticalAuditEvent criticalEvent = new CriticalAuditEvent(
                tenantId, locked.instance().id(), locked.task().id(), actionCode, actorId, requestId, occurredAt, snapshotHash, evidence);
        List<CriticalAuditCapability> matches = criticalAudits.stream().filter(capability -> capability.supports(criticalEvent)).toList();
        if (matches.size() > 1) throw invalidDefinition("critical workflow audit capability is ambiguous");
        if (matches.size() == 1) matches.getFirst().record(criticalEvent);
        repository.insertAction(new ActionEvidence(
                UUID.randomUUID(), tenantId, locked.instance().id(), locked.task().id(), actionCode,
                locked.task().status(), locked.task().status(), actorId, reason, occurredAt, requestId, snapshotHash), actorId);
    }

    private ObjectNode evidenceBase(Binding binding, Instant originalDueAt, Instant effectiveDueAt) {
        ObjectNode evidence = mapper.createObjectNode()
                .put("policyId", binding.policyId().toString())
                .put("durationMinutes", binding.policy().durationMinutes())
                .put("originalDueAt", originalDueAt.toString())
                .put("effectiveDueAt", effectiveDueAt.toString());
        if (binding.policy().calendarId() != null) evidence.put("calendarId", binding.policy().calendarId().toString());
        return evidence;
    }

    private SlaState stateFromEvidence(UUID taskId, ActionEvidence action) {
        JsonNode evidence = parseEvidence(action);
        Instant original = instant(evidence, "originalDueAt");
        Instant effective = instant(evidence, "effectiveDueAt");
        boolean paused = SLA_PAUSED.equals(action.actionCode());
        Instant pausedAt = paused && evidence.hasNonNull("pausedAt") ? instant(evidence, "pausedAt") : null;
        return new SlaState(taskId, original, effective, paused, pausedAt);
    }

    private JsonNode parseEvidence(ActionEvidence action) {
        try {
            return mapper.readTree(action.reason());
        } catch (JsonProcessingException ex) {
            throw invalidDefinition("stored SLA evidence cannot be parsed");
        }
    }

    private Instant evidenceInstant(ActionEvidence action, String field) {
        return instant(parseEvidence(action), field);
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw invalidDefinition("SLA evidence field " + field + " is not an instant");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidDefinition("SLA evidence field " + field + " is missing");
        }
        return value.textValue();
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw WorkflowException.invalid("SLA evidence cannot be serialized");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void validateIdentity(UUID tenantId, UUID taskId, UUID actorId, String requestId, Instant now) {
        if (tenantId == null || taskId == null || actorId == null || blank(requestId) || now == null) {
            throw WorkflowException.invalid("tenantId, taskId, actorId, requestId and timestamp are required");
        }
    }

    private static void validateDecision(Decision decision) {
        if (decision == null || decision.kind() == null || blank(decision.decisionKey())
                || decision.recipientIds() == null || decision.recipientIds().isEmpty()
                || decision.recipientIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw invalidDefinition("SLA rule evaluator returned an invalid decision");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static WorkflowException invalidDefinition(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, message);
    }

    public interface Repository {
        Optional<SlaTask> findTask(UUID tenantId, UUID taskId);
        Optional<SlaInstance> lockInstance(UUID tenantId, UUID instanceId);
        Optional<SlaTask> lockTask(UUID tenantId, UUID taskId);
        Optional<UUID> findNodeSlaPolicyId(UUID tenantId, UUID versionId, String nodeCode);
        Optional<SlaPolicy> findPolicy(UUID tenantId, UUID policyId);
        int updateTaskDueAt(UUID tenantId, UUID taskId, Instant dueAt, UUID actorId);
        int updateInstanceDueAt(UUID tenantId, UUID instanceId, Instant dueAt, UUID actorId);
        Optional<ActionEvidence> findFirstAction(UUID tenantId, UUID taskId, String actionCode);
        Optional<ActionEvidence> findLatestLifecycleAction(UUID tenantId, UUID taskId);
        Optional<ActionEvidence> findActionByRequestId(UUID tenantId, String requestId);
        void insertAction(ActionEvidence action, UUID actorId);
    }

    public interface WorkingCalendarCapability {
        boolean supports(UUID calendarId);
        Instant addWorkingMinutes(UUID tenantId, UUID calendarId, Instant start, long workingMinutes);
        long workingMinutesBetween(UUID tenantId, UUID calendarId, Instant start, Instant end);
    }

    public interface RuleEvaluatorCapability {
        boolean supports(SlaPolicy policy);
        List<Decision> evaluate(UUID tenantId, SlaPolicy policy, SlaInstance instance, SlaTask task, Instant now);
    }

    public interface NotificationCapability {
        boolean supports(DecisionKind kind);
        DeliveryReceipt deliver(PendingNotification notification);
    }

    public interface CriticalAuditCapability {
        boolean supports(CriticalAuditEvent event);
        void record(CriticalAuditEvent event);
    }

    public enum DecisionKind { REMINDER, ESCALATION }

    public record Decision(DecisionKind kind, String decisionKey, List<UUID> recipientIds) {
        public Decision { recipientIds = recipientIds == null ? null : List.copyOf(recipientIds); }
    }
    public record DeliveryReceipt(String providerReceipt) {}
    public record DeliveryResult(String decisionRequestId, String providerReceipt, boolean replayed) {}
    public record PendingNotification(
            UUID tenantId, UUID taskId, UUID instanceId, DecisionKind kind, String decisionKey,
            List<UUID> recipientIds, String decisionRequestId, Instant originalDueAt, Instant effectiveDueAt) {
        public PendingNotification { recipientIds = List.copyOf(recipientIds); }
    }
    public record SlaState(UUID taskId, Instant originalDueAt, Instant effectiveDueAt, boolean paused, Instant pausedAt) {}
    public record SlaPolicy(
            UUID id, String policyCode, String processCode, String nodeCode, int durationMinutes,
            UUID calendarId, JsonNode remindRules, JsonNode escalationRules) {}
    public record SlaTask(
            UUID id, UUID instanceId, String nodeCode, UUID assigneeId, String status, Instant receivedAt, Instant dueAt) {}
    public record SlaInstance(
            UUID id, UUID versionId, String processCode, String currentNodeCode, String status, Instant dueAt, JsonNode contextSnapshot) {}
    public record ActionEvidence(
            UUID id, UUID tenantId, UUID instanceId, UUID taskId, String actionCode,
            String fromStatus, String toStatus, UUID operatorId, String reason, Instant occurredAt,
            String requestId, String snapshotHash) {}
    public record CriticalAuditEvent(
            UUID tenantId, UUID instanceId, UUID taskId, String actionCode, UUID actorId,
            String requestId, Instant occurredAt, String snapshotHash, JsonNode evidence) {}

    private record Binding(UUID policyId, SlaPolicy policy) {}
    private record Locked(SlaInstance instance, SlaTask task) {}
}
