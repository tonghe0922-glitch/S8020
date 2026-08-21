package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowSlaServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void startPauseResumePreservesOriginalDueAndMovesOnlyEffectiveDue() {
        Fixture f = new Fixture();
        Instant started = Instant.parse("2026-08-08T00:00:00Z");
        var startedState = f.service.start(f.tenantId, f.taskId, f.actorId, "start-1", started);
        assertEquals(started.plus(Duration.ofMinutes(120)), startedState.originalDueAt());
        assertEquals(startedState.originalDueAt(), startedState.effectiveDueAt());

        Instant paused = started.plus(Duration.ofMinutes(30));
        var pausedState = f.service.pause(f.tenantId, f.taskId, f.actorId, "pause-1", paused);
        assertTrue(pausedState.paused());
        assertEquals(startedState.originalDueAt(), pausedState.originalDueAt());
        assertEquals(startedState.effectiveDueAt(), pausedState.effectiveDueAt());

        Instant resumed = paused.plus(Duration.ofMinutes(45));
        var resumedState = f.service.resume(f.tenantId, f.taskId, f.actorId, "resume-1", resumed);
        assertFalse(resumedState.paused());
        assertEquals(startedState.originalDueAt(), resumedState.originalDueAt());
        assertEquals(started.plus(Duration.ofMinutes(165)), resumedState.effectiveDueAt());
        assertEquals(resumedState.effectiveDueAt(), f.repository.task.dueAt());
        assertEquals(resumedState.effectiveDueAt(), f.repository.instance.dueAt());
    }

    @Test
    void missingOrAmbiguousCalendarFailsClosed() {
        Fixture f = new Fixture(List.of(), List.of(), List.of(), List.of());
        WorkflowException missing = assertThrows(WorkflowException.class, () -> f.service.start(
                f.tenantId, f.taskId, f.actorId, "start-missing", Instant.parse("2026-08-08T00:00:00Z")));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, missing.code());

        var calendar = new ContinuousCalendar();
        Fixture ambiguous = new Fixture(List.of(calendar, calendar), List.of(), List.of(), List.of());
        WorkflowException duplicate = assertThrows(WorkflowException.class, () -> ambiguous.service.start(
                ambiguous.tenantId, ambiguous.taskId, ambiguous.actorId, "start-ambiguous", Instant.parse("2026-08-08T00:00:00Z")));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, duplicate.code());
    }

    @Test
    void reminderAndEscalationDecisionsArePersistedBeforeRealDeliveryAndAreIdempotent() {
        CapturingNotification notification = new CapturingNotification();
        Fixture f = new Fixture(
                List.of(new ContinuousCalendar()), List.of(new FixedEvaluator(futureRecipientSeed())),
                List.of(notification), List.of());
        UUID recipient = f.recipientId;
        f.evaluator = new FixedEvaluator(recipient);
        f.service = f.newService();
        Instant start = Instant.parse("2026-08-08T00:00:00Z");
        f.service.start(f.tenantId, f.taskId, f.actorId, "start-events", start);

        List<WorkflowSlaService.PendingNotification> first = f.service.evaluate(
                f.tenantId, f.taskId, f.actorId, start.plus(Duration.ofMinutes(121)));
        assertEquals(2, first.size());
        List<WorkflowSlaService.PendingNotification> replay = f.service.evaluate(
                f.tenantId, f.taskId, f.actorId, start.plus(Duration.ofMinutes(122)));
        assertTrue(replay.isEmpty());

        var delivered = f.service.dispatch(first.getFirst(), f.actorId, start.plus(Duration.ofMinutes(123)));
        assertFalse(delivered.replayed());
        assertEquals(1, notification.deliveries);
        var deliveredReplay = f.service.dispatch(first.getFirst(), f.actorId, start.plus(Duration.ofMinutes(124)));
        assertTrue(deliveredReplay.replayed());
        assertEquals(1, notification.deliveries);
    }

    @Test
    void pausedSlaSuppressesEvaluationAndMissingNotificationNeverFakesSuccess() {
        Fixture f = new Fixture();
        Instant start = Instant.parse("2026-08-08T00:00:00Z");
        f.service.start(f.tenantId, f.taskId, f.actorId, "start-paused", start);
        f.service.pause(f.tenantId, f.taskId, f.actorId, "pause-paused", start.plusSeconds(60));
        assertTrue(f.service.evaluate(f.tenantId, f.taskId, f.actorId, start.plusSeconds(7200)).isEmpty());

        f.service.resume(f.tenantId, f.taskId, f.actorId, "resume-paused", start.plusSeconds(120));
        List<WorkflowSlaService.PendingNotification> due = f.service.evaluate(
                f.tenantId, f.taskId, f.actorId, start.plusSeconds(7500));
        assertFalse(due.isEmpty());
        f.notifications = List.of();
        f.service = f.newService();
        WorkflowException failure = assertThrows(WorkflowException.class, () ->
                f.service.dispatch(due.getFirst(), f.actorId, start.plusSeconds(7600)));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, failure.code());
    }

    @Test
    void classifiedCriticalAuditFailureFailsClosedBeforeWorkflowEvidence() {
        Fixture f = new Fixture();
        f.criticalAudits = List.of(new WorkflowSlaService.CriticalAuditCapability() {
            @Override public boolean supports(WorkflowSlaService.CriticalAuditEvent event) { return WorkflowSlaService.SLA_STARTED.equals(event.actionCode()); }
            @Override public void record(WorkflowSlaService.CriticalAuditEvent event) { throw new IllegalStateException("audit unavailable"); }
        });
        f.service = f.newService();
        assertThrows(IllegalStateException.class, () -> f.service.start(
                f.tenantId, f.taskId, f.actorId, "critical-start", Instant.parse("2026-08-08T00:00:00Z")));
        assertTrue(f.repository.actions.isEmpty());
    }

    private static UUID futureRecipientSeed() { return UUID.randomUUID(); }

    private final class Fixture {
        final UUID tenantId = UUID.randomUUID();
        final UUID taskId = UUID.randomUUID();
        final UUID instanceId = UUID.randomUUID();
        final UUID versionId = UUID.randomUUID();
        final UUID actorId = UUID.randomUUID();
        final UUID recipientId = UUID.randomUUID();
        final UUID policyId = UUID.randomUUID();
        final FakeRepository repository = new FakeRepository();
        List<WorkflowSlaService.WorkingCalendarCapability> calendars;
        List<WorkflowSlaService.RuleEvaluatorCapability> evaluators;
        List<WorkflowSlaService.NotificationCapability> notifications;
        List<WorkflowSlaService.CriticalAuditCapability> criticalAudits;
        FixedEvaluator evaluator;
        WorkflowSlaService service;

        Fixture() {
            this(List.of(new ContinuousCalendar()), List.of(), List.of(new CapturingNotification()), List.of());
            evaluator = new FixedEvaluator(recipientId);
            evaluators = List.of(evaluator);
            service = newService();
        }

        Fixture(
                List<WorkflowSlaService.WorkingCalendarCapability> calendars,
                List<WorkflowSlaService.RuleEvaluatorCapability> evaluators,
                List<WorkflowSlaService.NotificationCapability> notifications,
                List<WorkflowSlaService.CriticalAuditCapability> criticalAudits) {
            this.calendars = calendars;
            this.evaluators = evaluators;
            this.notifications = notifications;
            this.criticalAudits = criticalAudits;
            Instant received = Instant.parse("2026-08-08T00:00:00Z");
            repository.task = new WorkflowSlaService.SlaTask(
                    taskId, instanceId, "REVIEW", recipientId, WorkflowRuntimeService.PENDING, received, null);
            repository.instance = new WorkflowSlaService.SlaInstance(
                    instanceId, versionId, "P004", "REVIEW", WorkflowRuntimeService.RUNNING, null, mapper.createObjectNode());
            repository.policyId = policyId;
            repository.policy = new WorkflowSlaService.SlaPolicy(
                    policyId, "P004_REVIEW", "P004", "REVIEW", 120, null,
                    mapper.createObjectNode().put("opaque", "remind"), mapper.createObjectNode().put("opaque", "escalate"));
            service = newService();
        }

        WorkflowSlaService newService() {
            return new WorkflowSlaService(repository, mapper, calendars, evaluators, notifications, criticalAudits);
        }
    }

    private static final class ContinuousCalendar implements WorkflowSlaService.WorkingCalendarCapability {
        @Override public boolean supports(UUID calendarId) { return calendarId == null; }
        @Override public Instant addWorkingMinutes(UUID tenantId, UUID calendarId, Instant start, long workingMinutes) {
            return start.plus(Duration.ofMinutes(workingMinutes));
        }
        @Override public long workingMinutesBetween(UUID tenantId, UUID calendarId, Instant start, Instant end) {
            return Duration.between(start, end).toMinutes();
        }
    }

    private static final class FixedEvaluator implements WorkflowSlaService.RuleEvaluatorCapability {
        private final UUID recipient;
        private FixedEvaluator(UUID recipient) { this.recipient = recipient; }
        @Override public boolean supports(WorkflowSlaService.SlaPolicy policy) { return true; }
        @Override public List<WorkflowSlaService.Decision> evaluate(
                UUID tenantId, WorkflowSlaService.SlaPolicy policy, WorkflowSlaService.SlaInstance instance,
                WorkflowSlaService.SlaTask task, Instant now) {
            return List.of(
                    new WorkflowSlaService.Decision(WorkflowSlaService.DecisionKind.REMINDER, "R1", List.of(recipient)),
                    new WorkflowSlaService.Decision(WorkflowSlaService.DecisionKind.ESCALATION, "E1", List.of(recipient)));
        }
    }

    private static final class CapturingNotification implements WorkflowSlaService.NotificationCapability {
        int deliveries;
        @Override public boolean supports(WorkflowSlaService.DecisionKind kind) { return true; }
        @Override public WorkflowSlaService.DeliveryReceipt deliver(WorkflowSlaService.PendingNotification notification) {
            deliveries++;
            return new WorkflowSlaService.DeliveryReceipt("receipt-" + deliveries);
        }
    }

    private static final class FakeRepository implements WorkflowSlaService.Repository {
        WorkflowSlaService.SlaTask task;
        WorkflowSlaService.SlaInstance instance;
        UUID policyId;
        WorkflowSlaService.SlaPolicy policy;
        final List<WorkflowSlaService.ActionEvidence> actions = new ArrayList<>();

        @Override public Optional<WorkflowSlaService.SlaTask> findTask(UUID tenantId, UUID taskId) {
            return task != null && task.id().equals(taskId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<WorkflowSlaService.SlaInstance> lockInstance(UUID tenantId, UUID instanceId) {
            return instance != null && instance.id().equals(instanceId) ? Optional.of(instance) : Optional.empty();
        }
        @Override public Optional<WorkflowSlaService.SlaTask> lockTask(UUID tenantId, UUID taskId) { return findTask(tenantId, taskId); }
        @Override public Optional<UUID> findNodeSlaPolicyId(UUID tenantId, UUID versionId, String nodeCode) { return Optional.ofNullable(policyId); }
        @Override public Optional<WorkflowSlaService.SlaPolicy> findPolicy(UUID tenantId, UUID policyId) { return Optional.ofNullable(policy); }
        @Override public int updateTaskDueAt(UUID tenantId, UUID taskId, Instant dueAt, UUID actorId) {
            if (task == null || !task.id().equals(taskId)) return 0;
            task = new WorkflowSlaService.SlaTask(task.id(), task.instanceId(), task.nodeCode(), task.assigneeId(), task.status(), task.receivedAt(), dueAt);
            return 1;
        }
        @Override public int updateInstanceDueAt(UUID tenantId, UUID instanceId, Instant dueAt, UUID actorId) {
            if (instance == null || !instance.id().equals(instanceId)) return 0;
            instance = new WorkflowSlaService.SlaInstance(instance.id(), instance.versionId(), instance.processCode(), instance.currentNodeCode(), instance.status(), dueAt, instance.contextSnapshot());
            return 1;
        }
        @Override public Optional<WorkflowSlaService.ActionEvidence> findFirstAction(UUID tenantId, UUID taskId, String actionCode) {
            return actions.stream().filter(a -> taskId.equals(a.taskId()) && actionCode.equals(a.actionCode())).findFirst();
        }
        @Override public Optional<WorkflowSlaService.ActionEvidence> findLatestLifecycleAction(UUID tenantId, UUID taskId) {
            return actions.stream().filter(a -> taskId.equals(a.taskId()) && List.of(
                    WorkflowSlaService.SLA_STARTED, WorkflowSlaService.SLA_PAUSED, WorkflowSlaService.SLA_RESUMED).contains(a.actionCode()))
                    .reduce((first, second) -> second);
        }
        @Override public Optional<WorkflowSlaService.ActionEvidence> findActionByRequestId(UUID tenantId, String requestId) {
            return actions.stream().filter(a -> requestId.equals(a.requestId())).findFirst();
        }
        @Override public void insertAction(WorkflowSlaService.ActionEvidence action, UUID actorId) { actions.add(action); }
    }
}
