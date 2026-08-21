package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowOrchestrationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createUsesExplicitPhysicalSourceFieldsAndConfiguredNumberCapability() {
        Fixture f = new Fixture(List.of());
        var created = f.service.create(f.tenantId, f.actorId,
                new WorkflowOrchestrationService.CreateCommand("P120", null, f.source()));
        assertEquals("P120-1001", created.businessNo());
        assertEquals(WorkflowOrchestrationService.DRAFT, created.status());
        assertEquals(0, created.versionNo());
        assertEquals(BigDecimal.ZERO, created.completionRate());
        assertEquals("MASTER-001", created.masterOrderNo());
    }

    @Test
    void rejectsNonOrchestrationProcessAndIncompletePhysicalFields() {
        Fixture f = new Fixture(List.of());
        WorkflowException invalidProcess = assertThrows(WorkflowException.class, () -> f.service.create(
                f.tenantId, f.actorId, new WorkflowOrchestrationService.CreateCommand("P119", null, f.source())));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, invalidProcess.code());

        var source = f.source();
        var incomplete = new WorkflowOrchestrationService.PhysicalSourceFields(
                source.masterOrderNo(), source.leadCenterId(), source.participatingCenters(), source.currentMilestone(),
                source.criticalPath(), source.raciMatrix(), source.actualAmount(), source.actualEndAt(), source.actualStartAt(),
                source.businessDate(), source.contactName(), null, source.contentTitle(), source.contentType(), source.customerId(),
                source.customerName(), source.guestTeamName(), source.incidentAreaId(), source.incidentPatrolNo(),
                source.incidentType(), source.itemAssetId(), source.itemAssetName(), source.personName(), source.personNo(),
                source.programVersionId(), source.receptionLevel(), source.receptionTeamNo(), source.resultSummary(),
                source.showSessionNo(), source.showTime(), source.specModel(), source.targetJobId());
        WorkflowException missingField = assertThrows(WorkflowException.class, () -> f.service.create(
                f.tenantId, f.actorId, new WorkflowOrchestrationService.CreateCommand("P120", null, incomplete)));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, missingField.code());
    }

    @Test
    void itemAndLinkMutationsAdvanceVersionAndRejectStaleMutation() {
        Fixture f = new Fixture(List.of());
        var created = f.create();
        var withItem = f.service.addItem(f.tenantId, f.actorId, created.id(), 0,
                new WorkflowOrchestrationService.ItemCommand(
                        "child_orders_tasks", 0, "TASK-1", "Child task", "OPEN", null,
                        null, null, null, null, null, 10));
        assertEquals(1, withItem.versionNo());
        assertEquals(1, f.repository.items.size());

        UUID child = UUID.randomUUID();
        f.repository.workflowRefs.put(child, new WorkflowOrchestrationService.WorkflowReference(child, "P004", "RUNNING"));
        var withLink = f.service.addLink(f.tenantId, f.actorId, created.id(), 1,
                new WorkflowOrchestrationService.LinkCommand("P004", child, "SOURCE_DEFINED", "M1", "ACTIVE", true));
        assertEquals(2, withLink.versionNo());
        assertEquals(1, f.repository.links.size());

        WorkflowException stale = assertThrows(WorkflowException.class, () -> f.service.updateProgress(
                f.tenantId, f.actorId, created.id(), 1, "M2", new BigDecimal("0.5")));
        assertEquals(WorkflowException.Code.STALE_VERSION, stale.code());
    }

    @Test
    void childProcessMismatchFailsClosedWithoutAddingLink() {
        Fixture f = new Fixture(List.of());
        var created = f.create();
        UUID child = UUID.randomUUID();
        f.repository.workflowRefs.put(child, new WorkflowOrchestrationService.WorkflowReference(child, "P005", "RUNNING"));
        WorkflowException mismatch = assertThrows(WorkflowException.class, () -> f.service.addLink(
                f.tenantId, f.actorId, created.id(), 0,
                new WorkflowOrchestrationService.LinkCommand("P004", child, "SOURCE_DEFINED", null, "ACTIVE", true)));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, mismatch.code());
        assertTrue(f.repository.links.isEmpty());
        assertEquals(0, f.repository.current.versionNo());
    }

    @Test
    void statusMutationRequiresExactlyOneExplicitStateCapability() {
        Fixture missing = new Fixture(List.of());
        var created = missing.create();
        WorkflowException noProvider = assertThrows(WorkflowException.class, () -> missing.service.changeStatus(
                missing.tenantId, missing.actorId, created.id(), 0, "ACTIVE"));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, noProvider.code());

        StateProvider provider = new StateProvider();
        Fixture ambiguous = new Fixture(List.of(provider, provider));
        var ambiguousCreated = ambiguous.create();
        WorkflowException duplicate = assertThrows(WorkflowException.class, () -> ambiguous.service.changeStatus(
                ambiguous.tenantId, ambiguous.actorId, ambiguousCreated.id(), 0, "ACTIVE"));
        assertEquals(WorkflowException.Code.INVALID_DEFINITION, duplicate.code());
    }

    @Test
    void explicitStateCapabilityCanAdvanceAndCloseWhileOptimisticVersionIsEnforced() {
        Fixture f = new Fixture(List.of(new StateProvider()));
        var created = f.create();
        var active = f.service.changeStatus(f.tenantId, f.actorId, created.id(), 0, "ACTIVE");
        assertEquals("ACTIVE", active.status());
        assertEquals(1, active.versionNo());

        WorkflowException earlyClose = assertThrows(WorkflowException.class, () -> f.service.close(
                f.tenantId, f.actorId, created.id(), 1, f.source().actualStartAt().minusSeconds(1)));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, earlyClose.code());

        var closed = f.service.close(
                f.tenantId, f.actorId, created.id(), 1, f.source().actualStartAt().plusSeconds(3600));
        assertEquals("CLOSED", closed.status());
        assertEquals(2, closed.versionNo());
    }

    private final class Fixture {
        final UUID tenantId = UUID.randomUUID();
        final UUID actorId = UUID.randomUUID();
        final FakeRepository repository = new FakeRepository();
        final WorkflowOrchestrationService service;

        Fixture(List<WorkflowOrchestrationService.StateTransitionCapability> stateTransitions) {
            service = new WorkflowOrchestrationService(
                    repository, (tenant, actor, process) -> process + "-1001", stateTransitions);
        }

        WorkflowOrchestrationService.Orchestration create() {
            return service.create(tenantId, actorId,
                    new WorkflowOrchestrationService.CreateCommand("P120", null, source()));
        }

        WorkflowOrchestrationService.PhysicalSourceFields source() {
            return new WorkflowOrchestrationService.PhysicalSourceFields(
                    "MASTER-001", UUID.randomUUID(), mapper.createArrayNode().add("CENTER-A"), "M0",
                    mapper.createArrayNode().add("SOURCE-CRITICAL"), mapper.createObjectNode().put("source", "RACI"),
                    new BigDecimal("123.45"), null, Instant.parse("2026-08-08T00:00:00Z"), LocalDate.of(2026, 8, 8),
                    "Contact", "CONTENT-001", "Content title", "SOURCE_TYPE", "CUST-001", "Customer",
                    "Guest team", "AREA-001", "PATROL-001", "SOURCE_INCIDENT", "ASSET-001", "Asset",
                    "Person", "PERSON-001", "PROGRAM-001", "LEVEL-1", "TEAM-001", "Source result",
                    "SESSION-001", Instant.parse("2026-08-08T01:00:00Z"), "MODEL-001", "JOB-001");
        }
    }

    private static final class StateProvider implements WorkflowOrchestrationService.StateTransitionCapability {
        @Override public boolean supports(String processCode) { return "P120".equals(processCode); }
        @Override public void requireAllowed(String processCode, String currentStatus, String targetStatus, boolean closing) {
            if (closing) {
                if (!"ACTIVE".equals(currentStatus) || !"CLOSED".equals(targetStatus)) {
                    throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION, "test close transition rejected");
                }
            } else if (!"DRAFT".equals(currentStatus) || !"ACTIVE".equals(targetStatus)) {
                throw new WorkflowException(WorkflowException.Code.ILLEGAL_ACTION, "test status transition rejected");
            }
        }
        @Override public String closingStatus(String processCode, String currentStatus) { return "CLOSED"; }
    }

    private static final class FakeRepository implements WorkflowOrchestrationService.Repository {
        WorkflowOrchestrationService.Orchestration current;
        final List<WorkflowOrchestrationService.OrchestrationItem> items = new ArrayList<>();
        final List<WorkflowOrchestrationService.OrchestrationLink> links = new ArrayList<>();
        final Map<UUID, WorkflowOrchestrationService.WorkflowReference> workflowRefs = new LinkedHashMap<>();

        @Override public void insert(WorkflowOrchestrationService.Orchestration orchestration, UUID actorId) { current = orchestration; }
        @Override public Optional<WorkflowOrchestrationService.Orchestration> find(UUID tenantId, UUID orchestrationId) {
            return current != null && current.id().equals(orchestrationId) ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<WorkflowOrchestrationService.Orchestration> lock(UUID tenantId, UUID orchestrationId) { return find(tenantId, orchestrationId); }
        @Override public Optional<WorkflowOrchestrationService.WorkflowReference> findWorkflowInstance(UUID tenantId, UUID workflowInstanceId) {
            return Optional.ofNullable(workflowRefs.get(workflowInstanceId));
        }
        @Override public int bumpVersion(UUID tenantId, UUID orchestrationId, int expectedVersion, UUID actorId) {
            if (current == null || current.versionNo() != expectedVersion) return 0;
            current = copy(current, current.status(), current.versionNo() + 1, current.masterChangeVersion() + 1,
                    current.currentMilestone(), current.completionRate(), current.actualEndAt());
            return 1;
        }
        @Override public int updateProgress(UUID tenantId, UUID orchestrationId, int expectedVersion, String milestone, BigDecimal completionRate, UUID actorId) {
            if (current == null || current.versionNo() != expectedVersion) return 0;
            current = copy(current, current.status(), current.versionNo() + 1, current.masterChangeVersion() + 1,
                    milestone, completionRate, current.actualEndAt());
            return 1;
        }
        @Override public int updateStatus(UUID tenantId, UUID orchestrationId, int expectedVersion, String status, Instant actualEndAt, UUID actorId) {
            if (current == null || current.versionNo() != expectedVersion) return 0;
            current = copy(current, status, current.versionNo() + 1, current.masterChangeVersion() + 1,
                    current.currentMilestone(), current.completionRate(), actualEndAt == null ? current.actualEndAt() : actualEndAt);
            return 1;
        }
        @Override public boolean itemExists(UUID tenantId, UUID orchestrationId, String fieldCode, int itemSeq, String itemKey) {
            return items.stream().anyMatch(item -> item.masterId().equals(orchestrationId) && item.fieldCode().equals(fieldCode)
                    && item.itemSeq() == itemSeq && java.util.Objects.equals(item.itemKey(), itemKey));
        }
        @Override public void insertItem(WorkflowOrchestrationService.OrchestrationItem item, UUID actorId) { items.add(item); }
        @Override public List<WorkflowOrchestrationService.OrchestrationItem> listItems(UUID tenantId, UUID orchestrationId) { return List.copyOf(items); }
        @Override public boolean linkExists(UUID tenantId, UUID orchestrationId, UUID childInstanceId) {
            return links.stream().anyMatch(link -> link.orchestrationId().equals(orchestrationId) && link.childInstanceId().equals(childInstanceId));
        }
        @Override public void insertLink(WorkflowOrchestrationService.OrchestrationLink link, UUID actorId) { links.add(link); }
        @Override public List<WorkflowOrchestrationService.OrchestrationLink> listLinks(UUID tenantId, UUID orchestrationId) { return List.copyOf(links); }

        private static WorkflowOrchestrationService.Orchestration copy(
                WorkflowOrchestrationService.Orchestration o, String status, int version, int changeVersion,
                String milestone, BigDecimal completionRate, Instant actualEndAt) {
            return new WorkflowOrchestrationService.Orchestration(
                    o.id(), o.tenantId(), o.businessNo(), o.workflowInstanceId(), status, version, o.processCode(),
                    o.masterOrderNo(), o.leadCenterId(), o.participatingCenters(), milestone, o.criticalPath(), o.raciMatrix(),
                    changeVersion, completionRate, o.actualAmount(), actualEndAt, o.actualStartAt(), o.businessDate(), o.contactName(),
                    o.contentAssetNo(), o.contentTitle(), o.contentType(), o.customerId(), o.customerName(), o.guestTeamName(),
                    o.incidentAreaId(), o.incidentPatrolNo(), o.incidentType(), o.itemAssetId(), o.itemAssetName(), o.personName(),
                    o.personNo(), o.programVersionId(), o.receptionLevel(), o.receptionTeamNo(), o.resultSummary(),
                    o.showSessionNo(), o.showTime(), o.specModel(), o.targetJobId());
        }
    }
}
