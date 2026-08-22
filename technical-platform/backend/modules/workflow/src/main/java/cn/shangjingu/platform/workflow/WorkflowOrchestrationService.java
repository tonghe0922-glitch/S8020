package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowOrchestrationService {
    public static final String DRAFT = "DRAFT";
    private static final Set<String> ORCHESTRATION_PROCESS_CODES =
            Set.of("P120", "P121", "P122", "P123", "P124", "P125", "P126");

    private final Repository repository;
    private final OrchestrationNumberCapability numbers;
    private final List<StateTransitionCapability> stateTransitions;

    public WorkflowOrchestrationService(
            Repository repository,
            OrchestrationNumberCapability numbers,
            List<StateTransitionCapability> stateTransitions) {
        this.repository = repository;
        this.numbers = numbers;
        this.stateTransitions = List.copyOf(stateTransitions);
    }

    @Transactional
    public Orchestration create(UUID tenantId, UUID actorId, CreateCommand command) {
        if (tenantId == null || actorId == null || command == null) {
            throw WorkflowException.invalid("tenantId, actorId and orchestration command are required");
        }
        validateProcess(command.processCode());
        validateSourceFields(command.source());
        if (command.workflowInstanceId() != null) {
            WorkflowReference workflow = repository
                    .findWorkflowInstance(tenantId, command.workflowInstanceId())
                    .orElseThrow(() -> WorkflowException.notFound("parent workflow instance not found"));
            if (!command.processCode().equals(workflow.processCode())) {
                throw invalidDefinition("parent workflow process does not match orchestration process");
            }
        }

        String businessNo = numbers.next(tenantId, actorId, command.processCode());
        if (blank(businessNo)) throw invalidDefinition("business number capability returned an empty number");
        UUID id = UUID.randomUUID();
        Orchestration orchestration = new Orchestration(
                id,
                tenantId,
                businessNo,
                command.workflowInstanceId(),
                DRAFT,
                0,
                command.processCode(),
                command.source().masterOrderNo(),
                command.source().leadCenterId(),
                command.source().participatingCenters(),
                command.source().currentMilestone(),
                command.source().criticalPath(),
                command.source().raciMatrix(),
                0,
                BigDecimal.ZERO,
                command.source().actualAmount(),
                command.source().actualEndAt(),
                command.source().actualStartAt(),
                command.source().businessDate(),
                command.source().contactName(),
                command.source().contentAssetNo(),
                command.source().contentTitle(),
                command.source().contentType(),
                command.source().customerId(),
                command.source().customerName(),
                command.source().guestTeamName(),
                command.source().incidentAreaId(),
                command.source().incidentPatrolNo(),
                command.source().incidentType(),
                command.source().itemAssetId(),
                command.source().itemAssetName(),
                command.source().personName(),
                command.source().personNo(),
                command.source().programVersionId(),
                command.source().receptionLevel(),
                command.source().receptionTeamNo(),
                command.source().resultSummary(),
                command.source().showSessionNo(),
                command.source().showTime(),
                command.source().specModel(),
                command.source().targetJobId());
        repository.insert(orchestration, actorId);
        return orchestration;
    }

    @Transactional
    public Orchestration addItem(
            UUID tenantId, UUID actorId, UUID orchestrationId, int expectedVersion, ItemCommand item) {
        validateMutationIdentity(tenantId, actorId, orchestrationId, expectedVersion);
        validateItem(item);
        Orchestration current = lock(tenantId, orchestrationId, expectedVersion);
        if (repository.itemExists(tenantId, orchestrationId, item.fieldCode(), item.itemSeq(), item.itemKey())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "orchestration item identity already exists");
        }
        bump(tenantId, orchestrationId, expectedVersion, actorId);
        repository.insertItem(
                new OrchestrationItem(
                        UUID.randomUUID(),
                        tenantId,
                        orchestrationId,
                        item.fieldCode(),
                        item.itemSeq(),
                        item.itemKey(),
                        item.itemName(),
                        item.itemValueText(),
                        item.itemValueNumber(),
                        item.itemValueJson(),
                        item.relatedObjectType(),
                        item.relatedObjectId(),
                        item.amount(),
                        item.quantity(),
                        item.sortNo()),
                actorId);
        return repository
                .find(tenantId, orchestrationId)
                .orElseThrow(() -> WorkflowException.notFound("orchestration disappeared after item insert"));
    }

    @Transactional
    public Orchestration addLink(
            UUID tenantId, UUID actorId, UUID orchestrationId, int expectedVersion, LinkCommand link) {
        validateMutationIdentity(tenantId, actorId, orchestrationId, expectedVersion);
        validateLink(link);
        Orchestration current = lock(tenantId, orchestrationId, expectedVersion);
        WorkflowReference child = repository
                .findWorkflowInstance(tenantId, link.childInstanceId())
                .orElseThrow(() -> WorkflowException.notFound("child workflow instance not found"));
        if (!link.childProcessCode().equals(child.processCode())) {
            throw invalidDefinition("child workflow process code does not match link process code");
        }
        if (repository.linkExists(tenantId, orchestrationId, link.childInstanceId())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION, "child workflow instance is already linked");
        }
        bump(tenantId, orchestrationId, expectedVersion, actorId);
        repository.insertLink(
                new OrchestrationLink(
                        UUID.randomUUID(),
                        tenantId,
                        orchestrationId,
                        link.childProcessCode(),
                        link.childInstanceId(),
                        link.dependencyType(),
                        link.milestoneCode(),
                        link.status(),
                        link.required()),
                actorId);
        return repository
                .find(tenantId, orchestrationId)
                .orElseThrow(() -> WorkflowException.notFound("orchestration disappeared after link insert"));
    }

    @Transactional
    public Orchestration updateProgress(
            UUID tenantId,
            UUID actorId,
            UUID orchestrationId,
            int expectedVersion,
            String currentMilestone,
            BigDecimal completionRate) {
        validateMutationIdentity(tenantId, actorId, orchestrationId, expectedVersion);
        if (completionRate == null) throw WorkflowException.invalid("completionRate is required");
        Orchestration current = lock(tenantId, orchestrationId, expectedVersion);
        int changed = repository.updateProgress(
                tenantId, orchestrationId, expectedVersion, currentMilestone, completionRate, actorId);
        if (changed != 1) throw stale("orchestration progress concurrent update conflict");
        return required(tenantId, orchestrationId);
    }

    @Transactional
    public Orchestration changeStatus(
            UUID tenantId, UUID actorId, UUID orchestrationId, int expectedVersion, String targetStatus) {
        validateMutationIdentity(tenantId, actorId, orchestrationId, expectedVersion);
        if (blank(targetStatus)) throw WorkflowException.invalid("targetStatus is required");
        Orchestration current = lock(tenantId, orchestrationId, expectedVersion);
        StateTransitionCapability transition = transition(current.processCode());
        transition.requireAllowed(current.processCode(), current.status(), targetStatus, false);
        if (current.status().equals(targetStatus)) return current;
        int changed = repository.updateStatus(tenantId, orchestrationId, expectedVersion, targetStatus, null, actorId);
        if (changed != 1) throw stale("orchestration status concurrent update conflict");
        return required(tenantId, orchestrationId);
    }

    @Transactional
    public Orchestration close(
            UUID tenantId, UUID actorId, UUID orchestrationId, int expectedVersion, Instant closedAt) {
        validateMutationIdentity(tenantId, actorId, orchestrationId, expectedVersion);
        if (closedAt == null) throw WorkflowException.invalid("closedAt is required");
        Orchestration current = lock(tenantId, orchestrationId, expectedVersion);
        if (closedAt.isBefore(current.actualStartAt())) {
            throw WorkflowException.invalid("orchestration close time cannot precede actual start time");
        }
        StateTransitionCapability transition = transition(current.processCode());
        String targetStatus = transition.closingStatus(current.processCode(), current.status());
        if (blank(targetStatus)) throw invalidDefinition("state transition capability returned no closing status");
        transition.requireAllowed(current.processCode(), current.status(), targetStatus, true);
        int changed =
                repository.updateStatus(tenantId, orchestrationId, expectedVersion, targetStatus, closedAt, actorId);
        if (changed != 1) throw stale("orchestration close concurrent update conflict");
        return required(tenantId, orchestrationId);
    }

    public Optional<Orchestration> find(UUID tenantId, UUID orchestrationId) {
        if (tenantId == null || orchestrationId == null) return Optional.empty();
        return repository.find(tenantId, orchestrationId);
    }

    public List<OrchestrationItem> items(UUID tenantId, UUID orchestrationId) {
        if (tenantId == null || orchestrationId == null)
            throw WorkflowException.invalid("tenantId and orchestrationId are required");
        return repository.listItems(tenantId, orchestrationId);
    }

    public List<OrchestrationLink> links(UUID tenantId, UUID orchestrationId) {
        if (tenantId == null || orchestrationId == null)
            throw WorkflowException.invalid("tenantId and orchestrationId are required");
        return repository.listLinks(tenantId, orchestrationId);
    }

    private Orchestration lock(UUID tenantId, UUID orchestrationId, int expectedVersion) {
        Orchestration current = repository
                .lock(tenantId, orchestrationId)
                .orElseThrow(() -> WorkflowException.notFound("orchestration not found"));
        if (current.versionNo() != expectedVersion) throw stale("orchestration version conflict");
        return current;
    }

    private void bump(UUID tenantId, UUID orchestrationId, int expectedVersion, UUID actorId) {
        if (repository.bumpVersion(tenantId, orchestrationId, expectedVersion, actorId) != 1) {
            throw stale("orchestration concurrent update conflict");
        }
    }

    private Orchestration required(UUID tenantId, UUID orchestrationId) {
        return repository
                .find(tenantId, orchestrationId)
                .orElseThrow(() -> WorkflowException.notFound("orchestration not found"));
    }

    private StateTransitionCapability transition(String processCode) {
        List<StateTransitionCapability> matches = stateTransitions.stream()
                .filter(capability -> capability.supports(processCode))
                .toList();
        if (matches.size() != 1)
            throw invalidDefinition("orchestration state transition capability is unavailable or ambiguous");
        return matches.getFirst();
    }

    private static void validateProcess(String processCode) {
        if (!ORCHESTRATION_PROCESS_CODES.contains(processCode)) {
            throw WorkflowException.invalid("orchestration processCode must be P120-P126");
        }
    }

    private static void validateSourceFields(PhysicalSourceFields source) {
        if (source == null
                || blank(source.masterOrderNo())
                || source.leadCenterId() == null
                || source.participatingCenters() == null
                || source.actualStartAt() == null
                || source.businessDate() == null
                || blank(source.contentAssetNo())
                || blank(source.contentTitle())
                || blank(source.contentType())
                || blank(source.customerId())
                || blank(source.customerName())
                || blank(source.guestTeamName())
                || blank(source.incidentAreaId())
                || blank(source.incidentPatrolNo())
                || blank(source.incidentType())
                || blank(source.itemAssetId())
                || blank(source.itemAssetName())
                || blank(source.personName())
                || blank(source.personNo())
                || blank(source.programVersionId())
                || blank(source.receptionLevel())
                || blank(source.receptionTeamNo())
                || source.resultSummary() == null
                || blank(source.targetJobId())) {
            throw WorkflowException.invalid("approved orchestration physical source fields are incomplete");
        }
    }

    private static void validateItem(ItemCommand item) {
        if (item == null || blank(item.fieldCode()) || item.itemSeq() < 0) {
            throw WorkflowException.invalid("orchestration item fieldCode and non-negative itemSeq are required");
        }
        boolean hasValue = item.itemValueText() != null
                || item.itemValueNumber() != null
                || item.itemValueJson() != null
                || item.relatedObjectId() != null
                || item.amount() != null
                || item.quantity() != null;
        if (!hasValue) throw WorkflowException.invalid("orchestration item requires at least one source value");
        if (item.relatedObjectId() != null && blank(item.relatedObjectType())) {
            throw WorkflowException.invalid("relatedObjectType is required when relatedObjectId is present");
        }
    }

    private static void validateLink(LinkCommand link) {
        if (link == null
                || blank(link.childProcessCode())
                || link.childInstanceId() == null
                || blank(link.dependencyType())
                || blank(link.status())) {
            throw WorkflowException.invalid("child process, instance, dependency type and link status are required");
        }
    }

    private static void validateMutationIdentity(
            UUID tenantId, UUID actorId, UUID orchestrationId, int expectedVersion) {
        if (tenantId == null || actorId == null || orchestrationId == null || expectedVersion < 0) {
            throw WorkflowException.invalid(
                    "tenantId, actorId, orchestrationId and non-negative expectedVersion are required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static WorkflowException invalidDefinition(String message) {
        return new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, message);
    }

    private static WorkflowException stale(String message) {
        return new WorkflowException(WorkflowException.Code.STALE_VERSION, message);
    }

    public interface Repository {
        void insert(Orchestration orchestration, UUID actorId);

        Optional<Orchestration> find(UUID tenantId, UUID orchestrationId);

        Optional<Orchestration> lock(UUID tenantId, UUID orchestrationId);

        Optional<WorkflowReference> findWorkflowInstance(UUID tenantId, UUID workflowInstanceId);

        int bumpVersion(UUID tenantId, UUID orchestrationId, int expectedVersion, UUID actorId);

        int updateProgress(
                UUID tenantId,
                UUID orchestrationId,
                int expectedVersion,
                String milestone,
                BigDecimal completionRate,
                UUID actorId);

        int updateStatus(
                UUID tenantId,
                UUID orchestrationId,
                int expectedVersion,
                String status,
                Instant actualEndAt,
                UUID actorId);

        boolean itemExists(UUID tenantId, UUID orchestrationId, String fieldCode, int itemSeq, String itemKey);

        void insertItem(OrchestrationItem item, UUID actorId);

        List<OrchestrationItem> listItems(UUID tenantId, UUID orchestrationId);

        boolean linkExists(UUID tenantId, UUID orchestrationId, UUID childInstanceId);

        void insertLink(OrchestrationLink link, UUID actorId);

        List<OrchestrationLink> listLinks(UUID tenantId, UUID orchestrationId);
    }

    public interface OrchestrationNumberCapability {
        String next(UUID tenantId, UUID actorId, String processCode);
    }

    public interface StateTransitionCapability {
        boolean supports(String processCode);

        void requireAllowed(String processCode, String currentStatus, String targetStatus, boolean closing);

        String closingStatus(String processCode, String currentStatus);
    }

    public record CreateCommand(String processCode, UUID workflowInstanceId, PhysicalSourceFields source) {}

    public record PhysicalSourceFields(
            String masterOrderNo,
            UUID leadCenterId,
            JsonNode participatingCenters,
            String currentMilestone,
            JsonNode criticalPath,
            JsonNode raciMatrix,
            BigDecimal actualAmount,
            Instant actualEndAt,
            Instant actualStartAt,
            LocalDate businessDate,
            String contactName,
            String contentAssetNo,
            String contentTitle,
            String contentType,
            String customerId,
            String customerName,
            String guestTeamName,
            String incidentAreaId,
            String incidentPatrolNo,
            String incidentType,
            String itemAssetId,
            String itemAssetName,
            String personName,
            String personNo,
            String programVersionId,
            String receptionLevel,
            String receptionTeamNo,
            String resultSummary,
            String showSessionNo,
            Instant showTime,
            String specModel,
            String targetJobId) {}

    public record ItemCommand(
            String fieldCode,
            int itemSeq,
            String itemKey,
            String itemName,
            String itemValueText,
            BigDecimal itemValueNumber,
            JsonNode itemValueJson,
            String relatedObjectType,
            UUID relatedObjectId,
            BigDecimal amount,
            BigDecimal quantity,
            int sortNo) {}

    public record LinkCommand(
            String childProcessCode,
            UUID childInstanceId,
            String dependencyType,
            String milestoneCode,
            String status,
            boolean required) {}

    public record WorkflowReference(UUID id, String processCode, String status) {}

    public record OrchestrationItem(
            UUID id,
            UUID tenantId,
            UUID masterId,
            String fieldCode,
            int itemSeq,
            String itemKey,
            String itemName,
            String itemValueText,
            BigDecimal itemValueNumber,
            JsonNode itemValueJson,
            String relatedObjectType,
            UUID relatedObjectId,
            BigDecimal amount,
            BigDecimal quantity,
            int sortNo) {}

    public record OrchestrationLink(
            UUID id,
            UUID tenantId,
            UUID orchestrationId,
            String childProcessCode,
            UUID childInstanceId,
            String dependencyType,
            String milestoneCode,
            String status,
            boolean required) {}

    public record Orchestration(
            UUID id,
            UUID tenantId,
            String businessNo,
            UUID workflowInstanceId,
            String status,
            int versionNo,
            String processCode,
            String masterOrderNo,
            UUID leadCenterId,
            JsonNode participatingCenters,
            String currentMilestone,
            JsonNode criticalPath,
            JsonNode raciMatrix,
            int masterChangeVersion,
            BigDecimal completionRate,
            BigDecimal actualAmount,
            Instant actualEndAt,
            Instant actualStartAt,
            LocalDate businessDate,
            String contactName,
            String contentAssetNo,
            String contentTitle,
            String contentType,
            String customerId,
            String customerName,
            String guestTeamName,
            String incidentAreaId,
            String incidentPatrolNo,
            String incidentType,
            String itemAssetId,
            String itemAssetName,
            String personName,
            String personNo,
            String programVersionId,
            String receptionLevel,
            String receptionTeamNo,
            String resultSummary,
            String showSessionNo,
            Instant showTime,
            String specModel,
            String targetJobId) {}
}
