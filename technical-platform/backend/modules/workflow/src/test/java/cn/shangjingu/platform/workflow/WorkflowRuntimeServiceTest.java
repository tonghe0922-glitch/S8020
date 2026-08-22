package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeServiceTest {
    @Test
    void bindsPublishedVersionMovesByServerTransitionAndReplaysIdempotently() {
        Fixture f = new Fixture();
        var firstStart = f.start("idem-start");
        var replayStart = f.start("idem-start");
        assertTrue(replayStart.replayed());
        assertEquals(firstStart.instance().id(), replayStart.instance().id());
        assertEquals(f.versionId, firstStart.instance().versionId());
        assertEquals("START", firstStart.instance().currentNodeCode());
        assertEquals("START", firstStart.action().actionCode());

        var command = f.action(f.initiator, firstStart.instance().id(), null, "START", "SUBMIT", "idem-submit");
        var moved = f.service.act(command);
        var replayMoved = f.service.act(command);
        assertTrue(replayMoved.replayed());
        assertEquals(moved.action().id(), replayMoved.action().id());
        assertEquals(moved.task().id(), replayMoved.task().id());
        assertEquals("REVIEW", moved.instance().currentNodeCode());
        assertEquals("SUBMIT", moved.action().actionCode());
        assertEquals("START", moved.action().fromStatus());
        assertEquals("REVIEW", moved.action().toStatus());
        assertEquals(WorkflowRuntimeService.PENDING, moved.task().status());
        assertNull(moved.task().assigneeId());
        assertEquals(1, f.repository.instances.size());
    }

    @Test
    void serverAssignmentFailsClosedAndRejectReturnWithdrawRemainDistinct() {
        Fixture rejectFixture = new Fixture();
        var review = rejectFixture.toReview();
        UUID reviewer = UUID.randomUUID();
        WorkflowException noApprover = assertThrows(
                WorkflowException.class,
                () -> rejectFixture.service.act(rejectFixture.action(
                        reviewer, review.instance().id(), review.task().id(), "REVIEW", "APPROVE", "no-approver")));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, noApprover.code());

        rejectFixture.repository.assign(review.task().id(), reviewer);
        WorkflowException wrongActor = assertThrows(
                WorkflowException.class,
                () -> rejectFixture.service.act(rejectFixture.action(
                        UUID.randomUUID(),
                        review.instance().id(),
                        review.task().id(),
                        "REVIEW",
                        "APPROVE",
                        "wrong-actor")));
        assertEquals(WorkflowException.Code.FORBIDDEN, wrongActor.code());
        var rejected = rejectFixture.service.act(rejectFixture.action(
                reviewer, review.instance().id(), review.task().id(), "REVIEW", "REJECT", "reject"));
        assertEquals(WorkflowRuntimeService.REJECTED, rejected.instance().status());
        assertEquals("REJECT", rejected.action().actionCode());
        assertEquals(
                "REJECT", rejectFixture.repository.tasks.get(review.task().id()).resultCode());

        Fixture returnFixture = new Fixture();
        var returnReview = returnFixture.toReview();
        UUID returnReviewer = UUID.randomUUID();
        returnFixture.repository.assign(returnReview.task().id(), returnReviewer);
        var returned = returnFixture.service.act(returnFixture.action(
                returnReviewer,
                returnReview.instance().id(),
                returnReview.task().id(),
                "REVIEW",
                "RETURN",
                "return"));
        assertEquals(WorkflowRuntimeService.RUNNING, returned.instance().status());
        assertEquals("START", returned.instance().currentNodeCode());
        assertEquals("RETURN", returned.action().actionCode());

        Fixture withdrawFixture = new Fixture();
        var started = withdrawFixture.start("withdraw-start");
        WorkflowException forbidden = assertThrows(
                WorkflowException.class,
                () -> withdrawFixture.service.act(withdrawFixture.action(
                        UUID.randomUUID(), started.instance().id(), null, "START", "WITHDRAW", "withdraw-other")));
        assertEquals(WorkflowException.Code.FORBIDDEN, forbidden.code());
        var withdrawn = withdrawFixture.service.act(withdrawFixture.action(
                withdrawFixture.initiator, started.instance().id(), null, "START", "WITHDRAW", "withdraw"));
        assertEquals(WorkflowRuntimeService.WITHDRAWN, withdrawn.instance().status());
        assertEquals("WITHDRAW", withdrawn.action().actionCode());
    }

    @Test
    void illegalAndStaleCommandsCannotMoveState() {
        Fixture f = new Fixture();
        var started = f.start("stale-start");
        WorkflowException illegal = assertThrows(
                WorkflowException.class,
                () -> f.service.act(
                        f.action(f.initiator, started.instance().id(), null, "START", "APPROVE", "illegal")));
        assertEquals(WorkflowException.Code.ILLEGAL_ACTION, illegal.code());
        assertEquals(
                "START", f.repository.instances.get(started.instance().id()).currentNodeCode());

        f.service.act(f.action(f.initiator, started.instance().id(), null, "START", "SUBMIT", "submit"));
        WorkflowException stale = assertThrows(
                WorkflowException.class,
                () -> f.service.act(f.action(f.initiator, started.instance().id(), null, "START", "SUBMIT", "stale")));
        assertEquals(WorkflowException.Code.STALE_VERSION, stale.code());
    }

    @Test
    void transitionConditionsFailClosedWhenDslIsNotImplemented() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FailClosedTransitionConditionEvaluator evaluator = new FailClosedTransitionConditionEvaluator();
        assertTrue(evaluator.matches(null, null));
        assertTrue(evaluator.matches(mapper.readTree("{}"), null));
        assertFalse(evaluator.matches(mapper.readTree("false"), null));
        WorkflowException failure = assertThrows(
                WorkflowException.class,
                () -> evaluator.matches(mapper.readTree("{\"amount\":{\"gt\":100}}"), mapper.createObjectNode()));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, failure.code());
    }

    private static final class Fixture {
        final ObjectMapper mapper = new ObjectMapper();
        final UUID tenantId = UUID.randomUUID();
        final UUID definitionId = UUID.randomUUID();
        final UUID versionId = UUID.randomUUID();
        final UUID initiator = UUID.randomUUID();
        final UUID businessObjectId = UUID.randomUUID();
        final FakeRepository repository = new FakeRepository();
        final WorkflowRuntimeService service = new WorkflowRuntimeService(
                repository, new FakeIdempotency(), new FailClosedTransitionConditionEvaluator(), mapper);

        Fixture() {
            repository.version = new WorkflowRuntimeService.RuntimeVersion(
                    definitionId, versionId, "P900", WorkflowDefinitionService.PUBLISHED);
            repository.nodes.put(
                    "START", new WorkflowRuntimeService.RuntimeNode("START", "Start", "START", null, null, 10));
            repository.nodes.put(
                    "REVIEW",
                    new WorkflowRuntimeService.RuntimeNode(
                            "REVIEW",
                            "Review",
                            "TASK",
                            mapper.createObjectNode().put("resolver", "SERVER"),
                            null,
                            20));
            repository.nodes.put("END", new WorkflowRuntimeService.RuntimeNode("END", "End", "END", null, null, 30));
            repository.transitions.add(
                    new WorkflowRuntimeService.RuntimeTransition("START", "SUBMIT", "REVIEW", null, false));
            repository.transitions.add(
                    new WorkflowRuntimeService.RuntimeTransition("START", "WITHDRAW", "END", null, true));
            repository.transitions.add(
                    new WorkflowRuntimeService.RuntimeTransition("REVIEW", "APPROVE", "END", null, false));
            repository.transitions.add(
                    new WorkflowRuntimeService.RuntimeTransition("REVIEW", "REJECT", "END", null, true));
            repository.transitions.add(
                    new WorkflowRuntimeService.RuntimeTransition("REVIEW", "RETURN", "START", null, true));
        }

        WorkflowRuntimeService.Result start(String key) {
            return service.start(new WorkflowRuntimeService.StartCommand(
                    tenantId,
                    initiator,
                    UUID.randomUUID(),
                    versionId,
                    "TEST_CASE",
                    businessObjectId,
                    "BO-1",
                    "Test workflow",
                    "NORMAL",
                    mapper.createObjectNode().put("source", "test"),
                    key));
        }

        WorkflowRuntimeService.Result toReview() {
            var started = start("start-" + UUID.randomUUID());
            return service.act(
                    action(initiator, started.instance().id(), null, "START", "SUBMIT", "submit-" + UUID.randomUUID()));
        }

        WorkflowRuntimeService.ActionCommand action(
                UUID actor, UUID instance, UUID task, String expectedNode, String action, String key) {
            return new WorkflowRuntimeService.ActionCommand(
                    tenantId, actor, UUID.randomUUID(), instance, task, expectedNode, action, "test reason", key);
        }
    }

    private static final class FakeIdempotency implements WorkflowIdempotency {
        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public Claim claim(
                UUID tenantId,
                UUID actorId,
                String key,
                String requestHash,
                String resourceType,
                UUID proposedResourceId) {
            String id = tenantId + ":" + key;
            Entry existing = entries.get(id);
            if (existing != null) {
                if (!existing.hash.equals(requestHash) || !existing.type.equals(resourceType)) {
                    throw WorkflowException.conflict("idempotency key reused with different request");
                }
                return new Claim(existing.resourceId, true);
            }
            entries.put(id, new Entry(requestHash, resourceType, proposedResourceId));
            return new Claim(proposedResourceId, false);
        }

        private record Entry(String hash, String type, UUID resourceId) {}
    }

    private static final class FakeRepository implements WorkflowRuntimeService.Repository {
        WorkflowRuntimeService.RuntimeVersion version;
        final Map<String, WorkflowRuntimeService.RuntimeNode> nodes = new HashMap<>();
        final List<WorkflowRuntimeService.RuntimeTransition> transitions = new ArrayList<>();
        final Map<UUID, WorkflowRuntimeService.Instance> instances = new LinkedHashMap<>();
        final Map<UUID, WorkflowRuntimeService.Task> tasks = new LinkedHashMap<>();
        final Map<UUID, WorkflowRuntimeService.ActionLog> actions = new LinkedHashMap<>();

        @Override
        public Optional<WorkflowRuntimeService.RuntimeVersion> findPublishedVersion(UUID tenantId, UUID versionId) {
            return version != null && version.versionId().equals(versionId) ? Optional.of(version) : Optional.empty();
        }

        @Override
        public List<WorkflowRuntimeService.RuntimeNode> listNodesByType(
                UUID tenantId, UUID versionId, String nodeType) {
            return nodes.values().stream()
                    .filter(n -> n.nodeType().equalsIgnoreCase(nodeType))
                    .toList();
        }

        @Override
        public Optional<WorkflowRuntimeService.RuntimeNode> findNode(UUID tenantId, UUID versionId, String nodeCode) {
            return Optional.ofNullable(nodes.get(nodeCode));
        }

        @Override
        public List<WorkflowRuntimeService.RuntimeTransition> listTransitions(
                UUID tenantId, UUID versionId, String fromNodeCode, String actionCode) {
            return transitions.stream()
                    .filter(t -> t.fromNodeCode().equals(fromNodeCode)
                            && t.actionCode().equals(actionCode))
                    .toList();
        }

        @Override
        public void insertInstance(WorkflowRuntimeService.Instance instance, UUID actorId) {
            instances.put(instance.id(), instance);
        }

        @Override
        public Optional<WorkflowRuntimeService.Instance> findInstance(UUID tenantId, UUID instanceId) {
            return Optional.ofNullable(instances.get(instanceId));
        }

        @Override
        public Optional<WorkflowRuntimeService.Instance> lockInstance(UUID tenantId, UUID instanceId) {
            return findInstance(tenantId, instanceId);
        }

        @Override
        public int moveInstance(
                UUID tenantId,
                UUID instanceId,
                String expectedNodeCode,
                String targetNodeCode,
                String status,
                Instant finishedAt,
                UUID actorId) {
            var current = instances.get(instanceId);
            if (current == null
                    || !WorkflowRuntimeService.RUNNING.equals(current.status())
                    || !expectedNodeCode.equals(current.currentNodeCode())) return 0;
            instances.put(
                    instanceId,
                    new WorkflowRuntimeService.Instance(
                            current.id(),
                            current.tenantId(),
                            current.instanceNo(),
                            current.definitionId(),
                            current.versionId(),
                            current.processCode(),
                            current.businessObjectType(),
                            current.businessObjectId(),
                            current.businessObjectNo(),
                            current.title(),
                            current.initiatorId(),
                            targetNodeCode,
                            status,
                            current.priority(),
                            current.startedAt(),
                            finishedAt,
                            current.dueAt(),
                            current.contextSnapshot()));
            return 1;
        }

        @Override
        public void insertTask(WorkflowRuntimeService.Task task, UUID actorId) {
            tasks.put(task.id(), task);
        }

        @Override
        public Optional<WorkflowRuntimeService.Task> findCurrentTask(UUID tenantId, UUID instanceId, String nodeCode) {
            if (nodeCode == null) return Optional.empty();
            return tasks.values().stream()
                    .filter(t -> t.instanceId().equals(instanceId)
                            && t.nodeCode().equals(nodeCode)
                            && WorkflowRuntimeService.PENDING.equals(t.status()))
                    .reduce((a, b) -> b);
        }

        @Override
        public Optional<WorkflowRuntimeService.Task> lockTask(UUID tenantId, UUID taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public int completeTask(
                UUID tenantId, UUID taskId, UUID actorId, String resultCode, String comment, Instant completedAt) {
            var current = tasks.get(taskId);
            if (current == null || !WorkflowRuntimeService.PENDING.equals(current.status())) return 0;
            tasks.put(
                    taskId,
                    new WorkflowRuntimeService.Task(
                            current.id(),
                            current.tenantId(),
                            current.instanceId(),
                            current.taskNo(),
                            current.nodeCode(),
                            current.taskType(),
                            current.assigneeId(),
                            current.candidateRule(),
                            WorkflowRuntimeService.TASK_COMPLETED,
                            current.receivedAt(),
                            current.dueAt(),
                            completedAt,
                            resultCode,
                            comment));
            return 1;
        }

        @Override
        public void insertAction(WorkflowRuntimeService.ActionLog action, UUID actorId) {
            actions.put(action.id(), action);
        }

        @Override
        public Optional<WorkflowRuntimeService.ActionLog> findAction(UUID tenantId, UUID actionId) {
            return Optional.ofNullable(actions.get(actionId));
        }

        @Override
        public Optional<WorkflowRuntimeService.ActionLog> findActionByRequestId(UUID tenantId, String requestId) {
            return actions.values().stream()
                    .filter(a -> requestId.equals(a.requestId()))
                    .findFirst();
        }

        void assign(UUID taskId, UUID assigneeId) {
            var t = tasks.get(taskId);
            tasks.put(
                    taskId,
                    new WorkflowRuntimeService.Task(
                            t.id(),
                            t.tenantId(),
                            t.instanceId(),
                            t.taskNo(),
                            t.nodeCode(),
                            t.taskType(),
                            assigneeId,
                            t.candidateRule(),
                            t.status(),
                            t.receivedAt(),
                            t.dueAt(),
                            t.completedAt(),
                            t.resultCode(),
                            t.comment()));
        }
    }
}
