package cn.shangjingu.platform.iam.application;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class PermissionRequestService {
    public static final String PROCESS_CODE = "P002";
    public static final String EVENT_TYPE = "P002_PERMISSION_REQUEST_EVENT";
    public static final String AGGREGATE_TYPE = "P002_PERMISSION_REQUEST";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService taskAssignment;
    private final Repository repository;
    private final ObjectMapper mapper;

    public PermissionRequestService(
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

    public PermissionRequest create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validate(command);
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "iam.permission_request", proposedId, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), claim.resourceId());

            EmployeeSnapshot employee = repository.employee(actor.tenantId(), actor.employeeId())
                    .orElseThrow(() -> new ProcessRejectedException("P002 employee snapshot not found"));
            String authoritativeRisk = repository.roleRisk(actor.tenantId(), command.requestedRoleId());
            if (repository.hasOverlappingGrant(actor.tenantId(), actor.userId(), actor.identityId(),
                    command.requestedRoleId(), command.effectiveStartAt(), command.effectiveEndAt())) {
                throw new ProcessRejectedException("P002 requested role is already effective for the requested period");
            }
            UUID workflowVersion = repository.latestPublishedWorkflowVersion(actor.tenantId(), PROCESS_CODE)
                    .orElseThrow(() -> new ProcessRejectedException("P002 published workflow version is not configured"));
            List<UUID> reviewCandidates = repository.permissionCandidates(
                    actor.tenantId(), "p002.request.review", actor.orgId(), true, actor.employeeId());
            List<UUID> executionCandidates = repository.permissionCandidates(
                    actor.tenantId(), "p002.request.execute", null, false, actor.employeeId());
            List<UUID> revokeCandidates = repository.permissionCandidates(
                    actor.tenantId(), "p002.request.revoke", null, false, actor.employeeId());
            if (reviewCandidates.isEmpty() || executionCandidates.isEmpty() || revokeCandidates.isEmpty()) {
                throw new ProcessRejectedException("P002 has no eligible reviewer/executor/revoker configuration");
            }
            List<UUID> lifecycleCandidates = union(reviewCandidates, executionCandidates, revokeCandidates);

            String businessNo = numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE);
            PermissionRequest created = new PermissionRequest(
                    claim.resourceId(), actor.tenantId(), businessNo, null, label("S02"), 0,
                    normalize(command.sourceChannel(), "PORTAL"), command.businessDate(), command.subject().trim(),
                    trimToNull(command.reason()), normalize(command.priority(), "NORMAL"), authoritativeRisk,
                    actor.orgId(), null, actor.employeeId(), command.effectiveStartAt(), command.effectiveEndAt(), null,
                    null, null, null, actor.userId(), actor.identityId(), command.requestedRoleId(), null,
                    "REQUESTED", command.effectiveStartAt(), command.effectiveEndAt(), null, null);
            repository.insert(created, employee, actor.positionId(), actor.employeeId());
            repository.insertItems(actor.tenantId(), created.id(), actor.employeeId(), command);

            ObjectNode context = mapper.createObjectNode();
            context.put("riskLevel", created.riskLevel());
            context.put("ownerCenterId", created.ownerCenterId().toString());
            context.put("targetUserId", actor.userId().toString());
            context.put("targetIdentityId", actor.identityId().toString());
            context.put("requestedRoleId", command.requestedRoleId().toString());
            context.set("reviewCandidateIds", uuidArray(reviewCandidates));
            context.set("executionCandidateIds", uuidArray(executionCandidates));
            context.set("lifecycleCandidateIds", uuidArray(lifecycleCandidates));

            WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), workflowVersion,
                    "iam.permission_request", created.id(), created.businessNo(), created.subject(), created.priority(), context,
                    scopedKey(idempotencyKey, "start")));
            WorkflowRuntimeService.Result submitted = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    "S02", "SUBMIT", created.reason(), scopedKey(idempotencyKey, "submit")));
            int changed = repository.bindWorkflowAndMove(actor.tenantId(), created.id(), 0, submitted.instance().id(),
                    label(submitted.instance().currentNodeCode()), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P002 concurrent create transition conflict");
            emit(actor.tenantId(), actor.employeeId(), created.id(), "SUBMITTED", submitted.instance().currentNodeCode(),
                    reviewCandidates, created.businessNo());
            return required(actor.tenantId(), created.id());
        });
    }

    public PermissionRequest review(
            DatabaseSecurityContext actor, UUID id, String idempotencyKey, String requestHash, ReviewCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "review command");
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "iam.permission_request.review", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);
            PermissionRequest current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            String node = runtime.instance().currentNodeCode();
            String decision = normalize(command.decision(), "").toUpperCase(Locale.ROOT);
            String action;
            if (Set.of("S03", "S04", "S05").contains(node)) {
                if (!Set.of("APPROVE", "REJECT").contains(decision))
                    throw new ProcessRejectedException("P002 review decision is not allowed at " + node);
                if (runtime.task() == null) throw new ProcessRejectedException("P002 current review task is missing");
                taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(), runtime.task().id(), actor.employeeId()));
                action = decision;
                if ("S04".equals(node) && "APPROVE".equals(decision)) {
                    action = highRisk(current.riskLevel()) ? "APPROVE_HIGH" : "APPROVE_STANDARD";
                }
            } else if ("S07".equals(node)) {
                if (!Set.of("KEEP", "REVOKE").contains(decision))
                    throw new ProcessRejectedException("P002 periodic review decision is not allowed");
                if (runtime.task() == null) throw new ProcessRejectedException("P002 periodic review task is missing");
                taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(), runtime.task().id(), actor.employeeId()));
                action = "KEEP".equals(decision) ? "KEEP" : "REVOKE_REQUEST";
            } else {
                throw new ProcessRejectedException("P002 request is not at a reviewable source node");
            }
            WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(),
                    runtime.task() == null ? null : runtime.task().id(), node, action, command.reason(),
                    scopedKey(idempotencyKey, "workflow")));
            String targetStatus = label(result.instance().currentNodeCode());
            int changed = repository.moveStatus(actor.tenantId(), id, current.versionNo(), targetStatus,
                    command.reason(), result.instance().finishedAt(), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P002 concurrent review conflict");
            emit(actor.tenantId(), actor.employeeId(), id, "REVIEWED", result.instance().currentNodeCode(),
                    recipients(result), current.businessNo());
            return required(actor.tenantId(), id);
        });
    }

    public PermissionRequest execute(
            DatabaseSecurityContext actor, UUID id, String idempotencyKey, String requestHash, ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "execute command");
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "iam.permission_request.execute", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);
            PermissionRequest current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            if (!"S06".equals(runtime.instance().currentNodeCode()) || runtime.task() == null)
                throw new ProcessRejectedException("P002 request is not ready for permission execution");
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), runtime.task().id(), actor.employeeId()));
            UUID userRoleId = repository.activateGrant(actor.tenantId(), id, current.targetUserId(), current.targetIdentityId(),
                    current.requestedRoleId(), current.effectiveStartAt(), current.effectiveEndAt(), actor.employeeId());
            WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), runtime.task().id(),
                    "S06", "EXECUTE", command.reason(), scopedKey(idempotencyKey, "workflow")));
            int changed = repository.markExecutedAndMove(actor.tenantId(), id, current.versionNo(), userRoleId,
                    label(result.instance().currentNodeCode()), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P002 concurrent execute conflict");
            emit(actor.tenantId(), actor.employeeId(), id, "EXECUTED", result.instance().currentNodeCode(),
                    recipients(result), current.businessNo());
            return required(actor.tenantId(), id);
        });
    }

    public PermissionRequest revoke(
            DatabaseSecurityContext actor, UUID id, String idempotencyKey, String requestHash, ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "revoke command");
        return transactions.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "iam.permission_request.revoke", id, IDEMPOTENCY_TTL);
            if (claim.existing()) return required(actor.tenantId(), id);
            PermissionRequest current = required(actor.tenantId(), id);
            requireVersion(current, command.expectedVersion());
            WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            String node = runtime.instance().currentNodeCode();
            if ("S07".equals(node)) {
                if (runtime.task() == null) throw new ProcessRejectedException("P002 lifecycle task is missing");
                taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(), runtime.task().id(), actor.employeeId()));
                workflow.act(new WorkflowRuntimeService.ActionCommand(
                        actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), runtime.task().id(),
                        "S07", "REVOKE_REQUEST", command.reason(), scopedKey(idempotencyKey, "to-revoke")));
            } else if (!"S08".equals(node)) {
                throw new ProcessRejectedException("P002 request is not in an authoritative revoke state");
            }
            WorkflowRuntimeService.Result revokeRuntime = workflow.get(actor.tenantId(), current.workflowInstanceId());
            if (!"S08".equals(revokeRuntime.instance().currentNodeCode()) || revokeRuntime.task() == null)
                throw new ProcessRejectedException("P002 revoke workflow task is missing");
            taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                    actor.tenantId(), revokeRuntime.task().id(), actor.employeeId()));
            repository.revokeGrant(actor.tenantId(), id, actor.employeeId(), command.reason(), Instant.now());
            WorkflowRuntimeService.Result closed = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), revokeRuntime.task().id(),
                    "S08", "REVOKE", command.reason(), scopedKey(idempotencyKey, "close")));
            int changed = repository.markRevokedAndClose(actor.tenantId(), id, current.versionNo(), label("END"),
                    command.reason(), closed.instance().finishedAt(), actor.employeeId());
            if (changed != 1) throw new ProcessRejectedException("P002 concurrent revoke conflict");
            emit(actor.tenantId(), actor.employeeId(), id, "REVOKED", "END", List.of(current.ownerEmployeeId()), current.businessNo());
            return required(actor.tenantId(), id);
        });
    }

    public Optional<PermissionRequest> find(DatabaseSecurityContext actor, UUID id) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    public List<PermissionRequest> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.list(actor.tenantId()));
    }

    private PermissionRequest required(UUID tenantId, UUID id) {
        return repository.find(tenantId, id).orElseThrow(() -> new ProcessRejectedException("P002 permission request not found"));
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

    private void emit(UUID tenantId, UUID actorId, UUID requestId, String event, String node,
                      List<UUID> recipients, String businessNo) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("requestId", requestId.toString());
        payload.put("businessNo", businessNo);
        payload.put("event", event);
        payload.put("nodeCode", node);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        outbox.enqueue(new TransactionalOutboxService.Command(
                tenantId, actorId, AGGREGATE_TYPE, requestId, EVENT_TYPE, 1, json(payload),
                "p002:" + requestId + ":" + event.toLowerCase(Locale.ROOT) + ":" + node));
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException("P002 event payload cannot be serialized", ex); }
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode array = mapper.createArrayNode();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }

    @SafeVarargs
    private static List<UUID> union(List<UUID>... groups) {
        LinkedHashSet<UUID> values = new LinkedHashSet<>();
        for (List<UUID> group : groups) values.addAll(group);
        return List.copyOf(values);
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || actor.identityId() == null
                || actor.employeeId() == null || actor.orgId() == null || actor.positionId() == null) {
            throw new ProcessRejectedException("P002 authenticated employee context is required");
        }
    }

    private static void requireVersion(PermissionRequest request, int expectedVersion) {
        if (request.versionNo() != expectedVersion) throw new ProcessRejectedException("P002 permission request version conflict");
    }

    private static void validate(CreateCommand command) {
        Objects.requireNonNull(command, "create command");
        if (command.requestedRoleId() == null || command.effectiveStartAt() == null || command.effectiveEndAt() == null
                || !command.effectiveEndAt().isAfter(command.effectiveStartAt()) || command.businessDate() == null
                || blank(command.subject()) || blank(command.businessObjectType()) || blank(command.businessObjectNo())) {
            throw new ProcessRejectedException("P002 required request fields are missing or invalid");
        }
    }

    private static boolean highRisk(String riskLevel) {
        String value = normalize(riskLevel, "NORMAL").toUpperCase(Locale.ROOT);
        return "HIGH".equals(value) || "CRITICAL".equals(value);
    }

    private static String label(String nodeCode) {
        return switch (nodeCode) {
            case "S02" -> "个人/项目补充授权申请";
            case "S03" -> "业务负责人确认";
            case "S04" -> "数据责任人复核";
            case "S05" -> "高风险权限审批";
            case "S06" -> "权限生效";
            case "S07" -> "定期复核";
            case "S08" -> "到期/调岗/离职回收";
            case "END" -> "已关闭";
            default -> throw new ProcessRejectedException("P002 workflow returned an unknown source node: " + nodeCode);
        };
    }

    private static String scopedKey(String key, String suffix) {
        String value = key + ":" + suffix;
        if (value.length() > 128) throw new ProcessRejectedException("P002 idempotency key is too long");
        return value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String trimToNull(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public interface Repository {
        Optional<EmployeeSnapshot> employee(UUID tenantId, UUID employeeId);
        void requireRole(UUID tenantId, UUID roleId);
        String roleRisk(UUID tenantId, UUID roleId);
        boolean hasOverlappingGrant(UUID tenantId, UUID userId, UUID identityId, UUID roleId, Instant start, Instant end);
        Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);
        List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId, boolean sameOrg, UUID excludedEmployeeId);
        void insert(PermissionRequest request, EmployeeSnapshot employee, UUID positionId, UUID actorId);
        void insertItems(UUID tenantId, UUID requestId, UUID actorId, CreateCommand command);
        int bindWorkflowAndMove(UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId);
        int moveStatus(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary, Instant closedAt, UUID actorId);
        UUID activateGrant(UUID tenantId, UUID requestId, UUID userId, UUID identityId, UUID roleId, Instant start, Instant end, UUID actorId);
        int markExecutedAndMove(UUID tenantId, UUID id, int expectedVersion, UUID userRoleId, String status, UUID actorId);
        void revokeGrant(UUID tenantId, UUID requestId, UUID actorId, String reason, Instant revokedAt);
        int markRevokedAndClose(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary, Instant closedAt, UUID actorId);
        Optional<PermissionRequest> find(UUID tenantId, UUID id);
        List<PermissionRequest> list(UUID tenantId);
    }

    public record EmployeeSnapshot(String employeeNo, String personName) {}

    public record CreateCommand(
            UUID requestedRoleId,
            Instant effectiveStartAt,
            Instant effectiveEndAt,
            String businessObjectType,
            String businessObjectNo,
            String businessObjectName,
            String businessScopeId,
            String sourceChannel,
            LocalDate businessDate,
            String subject,
            String reason,
            String expectedResult,
            String priority,
            String riskLevel,
            String externalReferenceNo,
            JsonNode attachments) {}

    public record ReviewCommand(int expectedVersion, String decision, String reason) {}
    public record ActionCommand(int expectedVersion, String reason) {}

    public record PermissionRequest(
            UUID id, UUID tenantId, String businessNo, UUID workflowInstanceId, String status, int versionNo,
            String sourceChannel, LocalDate businessDate, String subject, String reason, String priority, String riskLevel,
            UUID ownerCenterId, UUID ownerDepartmentId, UUID ownerEmployeeId,
            Instant plannedStartAt, Instant plannedFinishAt, String resultSummary, Instant actualStartAt, Instant actualEndAt,
            Instant closedAt, UUID targetUserId, UUID targetIdentityId, UUID requestedRoleId, UUID userRoleId,
            String grantStatus, Instant effectiveStartAt, Instant effectiveEndAt, Instant executedAt, Instant revokedAt) {
        public LocalDate plannedEffectiveDate() { return effectiveStartAt.atZone(BUSINESS_ZONE).toLocalDate(); }
    }
}
