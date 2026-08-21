package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.org.domain.AppointmentRecord;
import cn.shangjingu.platform.org.domain.EmployeeRecord;
import cn.shangjingu.platform.org.domain.OrgDirectoryPort;
import cn.shangjingu.platform.org.domain.OrganizationUnit;
import cn.shangjingu.platform.org.domain.PositionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowTaskAssignmentServiceTest {
    @Test
    void eligibleApproverCanClaimAndSameClaimReplays() {
        Fixture f = new Fixture();
        UUID eligible = f.employee("APPROVER");
        f.appoint(eligible);

        var first = f.service.claim(new WorkflowTaskAssignmentService.ClaimCommand(f.tenantId, f.taskId, eligible));
        assertFalse(first.replayed());
        assertEquals(eligible, first.assigneeId());
        assertEquals(List.of(eligible), first.eligibleCandidateIds());
        assertEquals(eligible, f.repository.task.assigneeId());

        var replay = f.service.claim(new WorkflowTaskAssignmentService.ClaimCommand(f.tenantId, f.taskId, eligible));
        assertTrue(replay.replayed());
        assertEquals(eligible, replay.assigneeId());
    }

    @Test
    void nonCandidateAndSelfApprovalFailClosed() {
        Fixture f = new Fixture();
        UUID eligible = f.employee("APPROVER");
        UUID outsider = f.employee("OUTSIDER");
        f.appoint(eligible);

        WorkflowException forbidden = assertThrows(WorkflowException.class, () -> f.service.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(f.tenantId, f.taskId, outsider)));
        assertEquals(WorkflowException.Code.FORBIDDEN, forbidden.code());

        Fixture self = new Fixture();
        self.appoint(self.initiatorId);
        WorkflowException noApprover = assertThrows(WorkflowException.class, () -> self.service.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(self.tenantId, self.taskId, self.initiatorId)));
        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, noApprover.code());
    }

    @Test
    void staleNodeAndCompetingClaimCannotOverwriteAssignment() {
        Fixture f = new Fixture();
        UUID first = f.employee("FIRST");
        UUID second = f.employee("SECOND");
        f.appoint(first);
        f.appoint(second);
        f.repository.task = new WorkflowTaskAssignmentService.CandidateTask(
                f.taskId, f.instanceId, "OLD_NODE", null, f.rule, WorkflowRuntimeService.PENDING);
        WorkflowException staleNode = assertThrows(WorkflowException.class, () -> f.service.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(f.tenantId, f.taskId, first)));
        assertEquals(WorkflowException.Code.STALE_VERSION, staleNode.code());

        f.repository.task = new WorkflowTaskAssignmentService.CandidateTask(
                f.taskId, f.instanceId, "REVIEW", first, f.rule, WorkflowRuntimeService.PENDING);
        WorkflowException competing = assertThrows(WorkflowException.class, () -> f.service.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(f.tenantId, f.taskId, second)));
        assertEquals(WorkflowException.Code.STALE_VERSION, competing.code());
        assertEquals(first, f.repository.task.assigneeId());
    }

    private static final class Fixture {
        final ObjectMapper mapper = new ObjectMapper();
        final UUID tenantId = UUID.randomUUID();
        final UUID orgId = UUID.randomUUID();
        final UUID positionId = UUID.randomUUID();
        final UUID initiatorId = UUID.randomUUID();
        final UUID instanceId = UUID.randomUUID();
        final UUID taskId = UUID.randomUUID();
        final FakeDirectory directory = new FakeDirectory(tenantId, orgId, positionId);
        final WorkflowCandidateResolver resolver = new WorkflowCandidateResolver(directory);
        final FakeRepository repository = new FakeRepository();
        final com.fasterxml.jackson.databind.node.ObjectNode rule = mapper.createObjectNode()
                .put("resolver", WorkflowCandidateResolver.ORG_POSITION)
                .put("orgId", orgId.toString())
                .put("positionId", positionId.toString());
        final WorkflowTaskAssignmentService service = new WorkflowTaskAssignmentService(repository, resolver);

        Fixture() {
            directory.employees.put(initiatorId, employeeRecord(initiatorId, "INIT"));
            repository.instance = new WorkflowTaskAssignmentService.CandidateInstance(
                    instanceId, initiatorId, "REVIEW", WorkflowRuntimeService.RUNNING, mapper.createObjectNode());
            repository.task = new WorkflowTaskAssignmentService.CandidateTask(
                    taskId, instanceId, "REVIEW", null, rule, WorkflowRuntimeService.PENDING);
        }

        UUID employee(String code) {
            UUID id = UUID.randomUUID();
            directory.employees.put(id, employeeRecord(id, code));
            return id;
        }

        private EmployeeRecord employeeRecord(UUID id, String code) {
            return new EmployeeRecord(id, tenantId, code, code, "ACTIVE", LocalDate.now().minusYears(1), null, orgId, positionId);
        }

        void appoint(UUID employeeId) {
            directory.appointments.add(new AppointmentRecord(
                    UUID.randomUUID(), tenantId, employeeId, positionId, orgId, false,
                    LocalDate.now().minusDays(1), null, "ACTIVE"));
        }
    }

    private static final class FakeRepository implements WorkflowTaskAssignmentService.Repository {
        WorkflowTaskAssignmentService.CandidateTask task;
        WorkflowTaskAssignmentService.CandidateInstance instance;

        @Override public Optional<WorkflowTaskAssignmentService.CandidateTask> findTask(UUID tenantId, UUID taskId) {
            return task != null && task.id().equals(taskId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<WorkflowTaskAssignmentService.CandidateInstance> lockInstance(UUID tenantId, UUID instanceId) {
            return instance != null && instance.id().equals(instanceId) ? Optional.of(instance) : Optional.empty();
        }
        @Override public Optional<WorkflowTaskAssignmentService.CandidateTask> lockTask(UUID tenantId, UUID taskId) {
            return findTask(tenantId, taskId);
        }
        @Override public int claimTask(UUID tenantId, UUID taskId, UUID assigneeId, UUID actorId) {
            if (task == null || !task.id().equals(taskId) || task.assigneeId() != null
                    || !WorkflowRuntimeService.PENDING.equals(task.status())) return 0;
            task = new WorkflowTaskAssignmentService.CandidateTask(
                    task.id(), task.instanceId(), task.nodeCode(), assigneeId, task.candidateRule(), task.status());
            return 1;
        }
    }

    private static final class FakeDirectory implements OrgDirectoryPort {
        final UUID tenantId;
        final UUID orgId;
        final UUID positionId;
        final Map<UUID, EmployeeRecord> employees = new HashMap<>();
        final List<AppointmentRecord> appointments = new ArrayList<>();

        FakeDirectory(UUID tenantId, UUID orgId, UUID positionId) {
            this.tenantId = tenantId;
            this.orgId = orgId;
            this.positionId = positionId;
        }

        @Override public Optional<EmployeeRecord> findEmployee(UUID tenantId, UUID employeeId) {
            return this.tenantId.equals(tenantId) ? Optional.ofNullable(employees.get(employeeId)) : Optional.empty();
        }
        @Override public Optional<OrganizationUnit> findOrganization(UUID tenantId, UUID orgId) {
            return this.tenantId.equals(tenantId) && this.orgId.equals(orgId)
                    ? Optional.of(new OrganizationUnit(orgId, tenantId, "ORG", "Org", "CENTER", null, null, "ACTIVE"))
                    : Optional.empty();
        }
        @Override public Optional<PositionRecord> findPosition(UUID tenantId, UUID positionId) {
            return this.tenantId.equals(tenantId) && this.positionId.equals(positionId)
                    ? Optional.of(new PositionRecord(positionId, tenantId, "POS", "Approver", orgId, null, "ACTIVE"))
                    : Optional.empty();
        }
        @Override public List<AppointmentRecord> findActiveAppointments(UUID tenantId, UUID employeeId) {
            return appointments.stream().filter(a -> a.employeeId().equals(employeeId)).toList();
        }
        @Override public List<AppointmentRecord> findActiveAppointmentsByOrgAndPosition(UUID tenantId, UUID orgId, UUID positionId) {
            if (!this.tenantId.equals(tenantId) || !this.orgId.equals(orgId) || !this.positionId.equals(positionId)) return List.of();
            return appointments;
        }
        @Override public boolean hasActiveAppointment(UUID tenantId, UUID employeeId, UUID orgId, UUID positionId) {
            return findActiveAppointmentsByOrgAndPosition(tenantId, orgId, positionId).stream()
                    .anyMatch(a -> a.employeeId().equals(employeeId));
        }
    }
}
