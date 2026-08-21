package cn.shangjingu.platform.hr.profile;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import cn.shangjingu.platform.workflow.WorkflowTaskAssignmentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class ProfileChangeService {
    public static final String PROCESS_CODE = "P003";
    public static final String EVENT_TYPE = "P003_PROFILE_CHANGE_EVENT";
    public static final String AGGREGATE_TYPE = "P003_PROFILE_CHANGE";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService taskAssignment;
    private final Repository repository;
    private final ObjectMapper mapper;

    public ProfileChangeService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService taskAssignment,
            Repository repository,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.taskAssignment = taskAssignment;
        this.repository = repository;
        this.mapper = mapper;
    }

    public ProfileChange create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        List<PreparedChange> changes = prepare(command);
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "hr.employee_profile_change", proposedId, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());

            UUID workflowVersion = repository.latestPublishedWorkflowVersion(actor.tenantId(), PROCESS_CODE)
                    .orElseThrow(() -> new ProcessRejectedException("P003 published workflow version is not configured"));
            List<UUID> reviewers = repository.permissionCandidates(
                    actor.tenantId(), "p003.change.review", actor.orgId(), true, actor.employeeId());
            List<UUID> appliers = repository.permissionCandidates(
                    actor.tenantId(), "p003.change.apply", null, false, actor.employeeId());
            if (reviewers.stream().distinct().count() < 2) {
                throw new ProcessRejectedException("P003 requires two distinct eligible reviewers for critical review nodes");
            }
            if (appliers.isEmpty()) throw new ProcessRejectedException("P003 has no eligible authoritative profile applier");

            String businessNo = numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE);
            ProfileChange created = new ProfileChange(
                    claim.resourceId(), actor.tenantId(), businessNo, null, label("S03"), 0,
                    normalize(command.sourceChannel(), "PORTAL"), command.businessDate(), command.subject().trim(),
                    trimToNull(command.reason()), normalize(command.priority(), "NORMAL"), risk(changes),
                    actor.orgId(), null, actor.employeeId(), command.expectedEffectiveAt(), command.knownImpact(),
                    null, null, null, List.of());
            repository.insert(created, actor.employeeId());
            repository.insertChanges(created.tenantId(), created.id(), actor.employeeId(), changes);

            ObjectNode context = mapper.createObjectNode();
            context.put("ownerEmployeeId", actor.employeeId().toString());
            context.put("ownerCenterId", actor.orgId().toString());
            context.put("riskLevel", created.riskLevel());
            context.set("reviewCandidateIds", uuidArray(reviewers));
            context.set("applyCandidateIds", uuidArray(appliers));
            context.set("changedFieldCodes", textArray(changes.stream().map(PreparedChange::fieldCode).toList()));

            WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), workflowVersion,
                    "hr.employee_profile_change", created.id(), created.businessNo(), created.subject(), created.priority(),
                    context, scopedKey(idempotencyKey, "start")));
            WorkflowRuntimeService.Result submitted = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    "S03", "SUBMIT", created.reason(), scopedKey(idempotencyKey, "submit")));
            int changed = repository.bindWorkflowAndMove(actor.tenantId(), created.id(), 0, submitted.instance().id(),
                    label(submitted.instance().currentNodeCode()), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P003 concurrent create transition conflict");
            emit(actor.tenantId(), actor.employeeId(), created.id(), created.businessNo(), "SUBMITTED",
                    submitted.instance().currentNodeCode(), reviewers, changes.stream().map(PreparedChange::fieldCode).toList());
            return required(actor.tenantId(), created.id());
        });
    }

    public ProfileChange review(
            DatabaseSecurityContext actor, UUID id, String idempotencyKey, String requestHash, ReviewCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "review command");
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "hr.employee_profile_change.review", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);
            ProfileChange current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            if (actor.employeeId().equals(current.ownerEmployeeId())) {
                throw new ProcessRejectedException("P003 applicant cannot review their own profile change");
            }
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            String node = runtime.instance().currentNodeCode();
            if (!Set.of("S04", "S05").contains(node) || runtime.task() == null) {
                throw new ProcessRejectedException("P003 profile change is not at a reviewable source node");
            }
            if ("S05".equals(node) && repository.approvedAtNode(
                    actor.tenantId(), current.workflowInstanceId(), "S04", actor.employeeId())) {
                throw new ProcessRejectedException("P003 critical review nodes require distinct employees");
            }
            String decision = normalize(command.decision(), "").toUpperCase(Locale.ROOT);
            if (!Set.of("APPROVE", "REJECT").contains(decision)) {
                throw new ProcessRejectedException("P003 review decision is not allowed");
            }
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), runtime.task().id(), actor.employeeId()));
            WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), runtime.task().id(),
                    node, decision, command.reason(), scopedKey(idempotencyKey, "workflow")));
            int changed = repository.moveStatus(actor.tenantId(), id, current.versionNo(),
                    label(result.instance().currentNodeCode()), command.reason(), result.instance().finishedAt(), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P003 concurrent review conflict");
            ProfileChange updated = required(actor.tenantId(), id);
            emit(actor.tenantId(), actor.employeeId(), id, current.businessNo(), "REVIEWED",
                    result.instance().currentNodeCode(), recipients(result), fieldCodes(updated));
            return updated;
        });
    }

    public ProfileChange apply(
            DatabaseSecurityContext actor, UUID id, String idempotencyKey, String requestHash, ApplyCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "apply command");
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "hr.employee_profile_change.apply", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);
            ProfileChange current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            if (!"S06".equals(runtime.instance().currentNodeCode()) || runtime.task() == null) {
                throw new ProcessRejectedException("P003 profile change is not ready for authoritative apply");
            }
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), runtime.task().id(), actor.employeeId()));
            repository.applyAuthoritativeChanges(actor.tenantId(), id, current.ownerEmployeeId(), actor.employeeId());

            WorkflowRuntimeService.Result applied = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), runtime.task().id(),
                    "S06", "APPLY", command.reason(), scopedKey(idempotencyKey, "apply")));
            int version = current.versionNo();
            if (repository.moveStatus(actor.tenantId(), id, version, label("S07"), command.reason(), null, actor.employeeId()) != 1)
                throw new ProcessRejectedException("P003 concurrent apply conflict");
            version++;
            emit(actor.tenantId(), actor.employeeId(), id, current.businessNo(), "APPLIED", "S07",
                    recipients(applied), fieldCodes(current));

            WorkflowRuntimeService.Result syncRuntime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            if (!"S07".equals(syncRuntime.instance().currentNodeCode()) || syncRuntime.task() == null)
                throw new ProcessRejectedException("P003 projection synchronization task is missing");
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), syncRuntime.task().id(), actor.employeeId()));
            WorkflowRuntimeService.Result synced = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), syncRuntime.task().id(),
                    "S07", "SYNC", command.reason(), scopedKey(idempotencyKey, "sync")));
            if (repository.moveStatus(actor.tenantId(), id, version, label("S08"), command.reason(), null, actor.employeeId()) != 1)
                throw new ProcessRejectedException("P003 concurrent projection synchronization conflict");
            version++;
            emit(actor.tenantId(), actor.employeeId(), id, current.businessNo(), "SYNCED", "S08",
                    recipients(synced), fieldCodes(current));

            WorkflowRuntimeService.Result closeRuntime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            if (!"S08".equals(closeRuntime.instance().currentNodeCode()) || closeRuntime.task() == null)
                throw new ProcessRejectedException("P003 notification and audit task is missing");
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), closeRuntime.task().id(), actor.employeeId()));
            WorkflowRuntimeService.Result closed = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), closeRuntime.task().id(),
                    "S08", "CLOSE", command.reason(), scopedKey(idempotencyKey, "close")));
            if (repository.moveStatus(actor.tenantId(), id, version, label("END"), command.reason(), closed.instance().finishedAt(), actor.employeeId()) != 1)
                throw new ProcessRejectedException("P003 concurrent close conflict");
            ProfileChange result = required(actor.tenantId(), id);
            emit(actor.tenantId(), actor.employeeId(), id, current.businessNo(), "CLOSED", "END",
                    List.of(current.ownerEmployeeId()), fieldCodes(result));
            return result;
        });
    }

    public Optional<ProfileChange> find(DatabaseSecurityContext actor, UUID id) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public List<ProfileChange> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.list(actor.tenantId()));
    }

    private ProfileChange required(UUID tenantId, UUID id) {
        return repository.find(tenantId, id).orElseThrow(() -> new ProcessRejectedException("P003 profile change not found"));
    }

    private static List<PreparedChange> prepare(CreateCommand command) {
        Objects.requireNonNull(command, "create command");
        if (command.businessDate() == null || command.subject() == null || command.subject().isBlank()
                || command.changes() == null || command.changes().isEmpty()) {
            throw new ProcessRejectedException("P003 required request fields are missing");
        }
        Set<String> unique = new HashSet<>();
        List<PreparedChange> prepared = new ArrayList<>();
        for (FieldChange change : command.changes()) {
            if (change == null) throw new ProcessRejectedException("P003 change entry is required");
            ProfileFieldDefinition definition = ProfileFieldDefinition.fromCode(change.fieldCode());
            if (!unique.add(definition.code())) throw new ProcessRejectedException("P003 duplicate field change: " + definition.code());
            String value = definition.normalize(change.proposedValue());
            String proof = trimToNull(change.proofReference());
            if (definition.proofRequired() && proof == null) {
                throw new ProcessRejectedException("P003 proof is required for highly sensitive field: " + definition.code());
            }
            prepared.add(new PreparedChange(definition.code(), value, definition.sensitivity(), proof));
        }
        return List.copyOf(prepared);
    }

    private static String risk(List<PreparedChange> changes) {
        return changes.stream().anyMatch(change -> change.sensitivity().startsWith("P3")) ? "HIGH" : "MEDIUM";
    }

    private List<UUID> recipients(WorkflowRuntimeService.Result result) {
        if (result.task() == null || result.task().candidateRule() == null) return List.of();
        JsonNode field = result.task().candidateRule().get("field");
        if (field == null || !field.isTextual()) return List.of();
        JsonNode values = result.instance().contextSnapshot() == null ? null : result.instance().contextSnapshot().get(field.textValue());
        if (values == null || !values.isArray()) return List.of();
        List<UUID> ids = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual()) try { ids.add(UUID.fromString(value.textValue())); } catch (IllegalArgumentException ignored) {} });
        return List.copyOf(ids);
    }

    private void emit(UUID tenantId, UUID actorId, UUID id, String businessNo, String event, String node,
                      List<UUID> recipients, List<String> changedFields) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("requestId", id.toString());
        payload.put("businessNo", businessNo);
        payload.put("event", event);
        payload.put("nodeCode", node);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        payload.set("changedFieldCodes", textArray(changedFields));
        outbox.enqueue(new TransactionalOutboxService.Command(
                tenantId, actorId, AGGREGATE_TYPE, id, EVENT_TYPE, 1, json(payload),
                "p003:" + id + ":" + event.toLowerCase(Locale.ROOT) + ":" + node));
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException("P003 event payload cannot be serialized", ex); }
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode array = mapper.createArrayNode();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }

    private ArrayNode textArray(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static List<String> fieldCodes(ProfileChange change) {
        return change.changes().stream().map(ChangeView::fieldCode).toList();
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || actor.identityId() == null
                || actor.employeeId() == null || actor.orgId() == null || actor.positionId() == null) {
            throw new ProcessRejectedException("P003 authenticated employee context is required");
        }
    }

    private static void requireVersion(ProfileChange change, int expectedVersion) {
        if (change.versionNo() != expectedVersion) throw new ProcessRejectedException("P003 profile change version conflict");
    }

    private static String label(String node) {
        return switch (node) {
            case "S03" -> "提交新值与证明";
            case "S04" -> "字段敏感级别校验";
            case "S05" -> "人事/财务/归口岗核验";
            case "S06" -> "权威主档更新";
            case "S07" -> "关联模块投影同步";
            case "S08" -> "通知与审计";
            case "END" -> "已关闭";
            default -> throw new ProcessRejectedException("P003 workflow returned an unknown source node: " + node);
        };
    }

    private static String scopedKey(String key, String suffix) {
        String value = key + ":" + suffix;
        if (value.length() > 128) throw new ProcessRejectedException("P003 idempotency key is too long");
        return value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public interface Repository {
        Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);
        List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId, boolean sameOrg, UUID excludedEmployeeId);
        void insert(ProfileChange change, UUID actorId);
        void insertChanges(UUID tenantId, UUID requestId, UUID actorId, List<PreparedChange> changes);
        int bindWorkflowAndMove(UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId);
        int moveStatus(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary, Instant closedAt, UUID actorId);
        boolean approvedAtNode(UUID tenantId, UUID workflowInstanceId, String nodeCode, UUID employeeId);
        void applyAuthoritativeChanges(UUID tenantId, UUID requestId, UUID employeeId, UUID actorId);
        Optional<ProfileChange> find(UUID tenantId, UUID id);
        List<ProfileChange> list(UUID tenantId);
    }

    public record FieldChange(String fieldCode, String proposedValue, String proofReference) {}
    public record PreparedChange(String fieldCode, String normalizedValue, String sensitivity, String proofReference) {}
    public record ChangeView(String fieldCode, String sensitivity, String proposedValueMasked, boolean proofProvided) {}
    public record CreateCommand(
            String sourceChannel, LocalDate businessDate, String subject, String reason, String priority,
            Instant expectedEffectiveAt, String knownImpact, List<FieldChange> changes) {}
    public record ReviewCommand(int expectedVersion, String decision, String reason) {}
    public record ApplyCommand(int expectedVersion, String reason) {}
    public record ProfileChange(
            UUID id, UUID tenantId, String businessNo, UUID workflowInstanceId, String status, int versionNo,
            String sourceChannel, LocalDate businessDate, String subject, String reason, String priority, String riskLevel,
            UUID ownerCenterId, UUID ownerDepartmentId, UUID ownerEmployeeId, Instant expectedEffectiveAt,
            String knownImpact, String resultSummary, Instant closedAt, Instant updatedAt, List<ChangeView> changes) {}
}
