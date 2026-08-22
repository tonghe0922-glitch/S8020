package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowTaskAssignmentService {
    private final Repository repository;
    private final WorkflowCandidateResolver candidateResolver;

    public WorkflowTaskAssignmentService(Repository repository, WorkflowCandidateResolver candidateResolver) {
        this.repository = repository;
        this.candidateResolver = candidateResolver;
    }

    @Transactional
    public ClaimResult claim(ClaimCommand command) {
        validate(command);
        CandidateTask snapshot = repository
                .findTask(command.tenantId(), command.taskId())
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));
        CandidateInstance instance = repository
                .lockInstance(command.tenantId(), snapshot.instanceId())
                .orElseThrow(() -> WorkflowException.notFound("workflow instance not found"));
        CandidateTask task = repository
                .lockTask(command.tenantId(), command.taskId())
                .orElseThrow(() -> WorkflowException.notFound("workflow task not found"));

        if (!task.instanceId().equals(instance.id())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow task instance changed while claiming");
        }
        if (!WorkflowRuntimeService.RUNNING.equals(instance.status())
                || !task.nodeCode().equals(instance.currentNodeCode())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION,
                    "workflow task no longer belongs to the current running node");
        }
        if (!WorkflowRuntimeService.PENDING.equals(task.status())) {
            throw new WorkflowException(WorkflowException.Code.STALE_VERSION, "workflow task is no longer pending");
        }
        if (task.assigneeId() != null) {
            if (task.assigneeId().equals(command.claimantId())) {
                return new ClaimResult(
                        task.id(), task.instanceId(), command.claimantId(), List.of(command.claimantId()), true);
            }
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow task has already been claimed by another approver");
        }

        WorkflowCandidateResolver.Resolution resolution = candidateResolver.resolve(
                command.tenantId(), instance.initiatorId(), task.candidateRule(), instance.contextSnapshot());
        if (!resolution.candidateIds().contains(command.claimantId())) {
            throw new WorkflowException(
                    WorkflowException.Code.FORBIDDEN, "claimant is not an eligible workflow approver");
        }

        int changed = repository.claimTask(command.tenantId(), task.id(), command.claimantId(), command.claimantId());
        if (changed != 1) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "workflow task changed concurrently while claiming");
        }
        return new ClaimResult(task.id(), task.instanceId(), command.claimantId(), resolution.candidateIds(), false);
    }

    private static void validate(ClaimCommand command) {
        if (command == null) throw WorkflowException.invalid("claim command is required");
        if (command.tenantId() == null) throw WorkflowException.invalid("tenantId is required");
        if (command.taskId() == null) throw WorkflowException.invalid("taskId is required");
        if (command.claimantId() == null) throw WorkflowException.invalid("claimantId is required");
    }

    public interface Repository {
        Optional<CandidateTask> findTask(UUID tenantId, UUID taskId);

        Optional<CandidateInstance> lockInstance(UUID tenantId, UUID instanceId);

        Optional<CandidateTask> lockTask(UUID tenantId, UUID taskId);

        int claimTask(UUID tenantId, UUID taskId, UUID assigneeId, UUID actorId);
    }

    public record ClaimCommand(UUID tenantId, UUID taskId, UUID claimantId) {}

    public record ClaimResult(
            UUID taskId, UUID instanceId, UUID assigneeId, List<UUID> eligibleCandidateIds, boolean replayed) {
        public ClaimResult {
            eligibleCandidateIds = List.copyOf(eligibleCandidateIds);
        }
    }

    public record CandidateTask(
            UUID id, UUID instanceId, String nodeCode, UUID assigneeId, JsonNode candidateRule, String status) {}

    public record CandidateInstance(
            UUID id, UUID initiatorId, String currentNodeCode, String status, JsonNode contextSnapshot) {}
}
