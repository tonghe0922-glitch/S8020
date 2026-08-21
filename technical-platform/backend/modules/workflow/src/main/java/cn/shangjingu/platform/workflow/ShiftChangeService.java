package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P007 scheduling and employee shift-change lifecycle. */
@Service
public final class ShiftChangeService {
    public static final String PROCESS_CODE = "P007";
    public static final String FORM_CODE = "CTR-P007-F01";
    public static final String MANAGE_PERMISSION = "p007.schedule.manage";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> CHANGE_ACTIONS = Set.of("SCHEDULE", "SHIFT_CHANGE");

    private final TenantTransactionRunner tx;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService tasks;
    private final WorkflowFormService forms;
    private final Repository repository;
    private final ObjectMapper mapper;

    public ShiftChangeService(
            TenantTransactionRunner tx,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService tasks,
            WorkflowFormService forms,
            Repository repository,
            ObjectMapper mapper) {
        this.tx = tx;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.tasks = tasks;
        this.forms = forms;
        this.repository = repository;
        this.mapper = mapper;
    }

    public Aggregate create(
            DatabaseSecurityContext actor,
            String key,
            String hash,
            CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        String requestedAction = changeAction(command.changeAction());
        if (!actor.orgId().equals(command.ownerCenterId())) {
            throw new ProcessRejectedException(
                    "P007 owner center must equal authenticated center");
        }
        return tx.required(actor, () -> {
            IdempotencyClaim claim =
                    idempotency.claim(
                            actor.tenantId(),
                            actor.employeeId(),
                            key,
                            hash,
                            "attendance.shift_change_request",
                            UUID.randomUUID(),
                            IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return aggregate(actor.tenantId(), claim.resourceId());
            }
            if (!repository.isActiveEmployeeInOrg(
                    actor.tenantId(), actor.orgId(), command.targetEmployeeId())) {
                throw new ProcessRejectedException(
                        "P007 target employee must be active in authenticated center");
            }
            UUID version =
                    repository.workflowVersion(actor.tenantId())
                            .orElseThrow(
                                    () ->
                                            new ProcessRejectedException(
                                                    "P007 workflow is not published"));
            FormRef form =
                    repository.form(actor.tenantId())
                            .orElseThrow(
                                    () ->
                                            new ProcessRejectedException(
                                                    "P007 form is not published"));
            List<UUID> managers =
                    repository.permissionCandidates(
                            actor.tenantId(), MANAGE_PERMISSION, actor.orgId());
            if (managers.isEmpty()) {
                throw new ProcessRejectedException(
                        "P007 schedule manager candidate is missing");
            }

            boolean employeeSelfService =
                    actor.employeeId().equals(command.targetEmployeeId())
                            && "SHIFT_CHANGE".equals(requestedAction);
            if (!employeeSelfService && !managers.contains(actor.employeeId())) {
                throw new ProcessRejectedException(
                        "P007 schedule creation requires an eligible manager candidate");
            }

            BigDecimal durationHours = hours(command.startAt(), command.endAt());
            ShiftRecord record =
                    new ShiftRecord(
                            claim.resourceId(),
                            actor.tenantId(),
                            numbers.next(
                                    actor.tenantId(), actor.employeeId(), PROCESS_CODE),
                            null,
                            null,
                            "S01",
                            label("S01"),
                            0,
                            command.subject().trim(),
                            trim(command.reason()),
                            actor.orgId(),
                            actor.employeeId(),
                            command.targetEmployeeId(),
                            null,
                            requestedAction,
                            command.changeReason().trim(),
                            trim(command.templateCode()),
                            command.periodOrCourseNo().trim(),
                            command.startAt(),
                            command.endAt(),
                            durationHours,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Instant.now());
            repository.insert(record, actor.employeeId());

            ObjectNode context = mapper.createObjectNode();
            context.put("ownerEmployeeId", actor.employeeId().toString());
            context.put("ownerCenterId", actor.orgId().toString());
            context.set("managerCandidateIds", uuidArray(managers));
            context.set(
                    "targetEmployeeIds",
                    uuidArray(List.of(command.targetEmployeeId())));

            WorkflowRuntimeService.Result started =
                    workflow.start(
                            new WorkflowRuntimeService.StartCommand(
                                    actor.tenantId(),
                                    actor.employeeId(),
                                    actor.identityId(),
                                    version,
                                    "attendance.shift_change_request",
                                    record.id(),
                                    record.businessNo(),
                                    record.subject(),
                                    "NORMAL",
                                    context,
                                    scope(key, "start")));
            forms.submit(
                    new WorkflowFormService.SubmitForm(
                            actor.tenantId(),
                            actor.employeeId(),
                            actor.identityId(),
                            started.instance().id(),
                            null,
                            form.id(),
                            form.versionNo(),
                            List.of(
                                    text("subject", record.subject()),
                                    text(
                                            "target_employee_id",
                                            record.targetEmployeeId().toString()),
                                    text("start_at", record.startAt().toString()),
                                    text("end_at", record.endAt().toString()),
                                    text("change_action", record.changeAction())),
                            scope(key, "form")));
            WorkflowRuntimeService.Result moved =
                    workflow.act(
                            new WorkflowRuntimeService.ActionCommand(
                                    actor.tenantId(),
                                    actor.employeeId(),
                                    actor.identityId(),
                                    started.instance().id(),
                                    null,
                                    "S01",
                                    "SUBMIT_DEMAND",
                                    null,
                                    scope(key, "s01")));
            required(
                    repository.bindAndMove(
                            actor.tenantId(),
                            record.id(),
                            0,
                            moved.instance().id(),
                            label("S02"),
                            actor.employeeId()),
                    "P007 concurrent create conflict");
            emit(
                    actor,
                    record,
                    "SUBMIT_DEMAND",
                    "S02",
                    List.of(command.targetEmployeeId()));
            return aggregate(actor.tenantId(), record.id());
        });
    }

    public Aggregate act(
            DatabaseSecurityContext actor,
            UUID id,
            String actionCode,
            String key,
            String hash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P007 action command is required");
        String action = safe(actionCode);
        return tx.required(actor, () -> {
            Aggregate current = aggregate(actor.tenantId(), id);
            IdempotencyClaim claim =
                    idempotency.claim(
                            actor.tenantId(),
                            actor.employeeId(),
                            key,
                            hash,
                            "attendance.shift_change_request.action."
                                    + action.toLowerCase(Locale.ROOT),
                            id,
                            IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return current;
            }
            if (current.record().versionNo() != command.expectedVersion()) {
                throw new ProcessRejectedException("P007 version conflict");
            }
            String node = current.record().currentNodeCode();
            switch (node) {
                case "S02" -> require(action, "MATCH_TEMPLATE");
                case "S03" -> {
                    require(action, "VALIDATE_SHIFT");
                    validateServerFacts(actor, current.record());
                    required(
                            repository.markValidated(
                                    actor.tenantId(),
                                    id,
                                    hours(
                                            current.record().startAt(),
                                            current.record().endAt()),
                                    actor.employeeId()),
                            "P007 qualification/continuous-work validation fact failed");
                    current = aggregate(actor.tenantId(), id);
                }
                case "S04" -> require(action, "PUBLISH_SCHEDULE");
                case "S05" -> {
                    require(action, "CONFIRM_SCHEDULE");
                    if (!actor.employeeId().equals(
                            current.record().targetEmployeeId())) {
                        throw new ProcessRejectedException(
                                "P007 only target employee may confirm schedule");
                    }
                    required(
                            repository.markConfirmed(
                                    actor.tenantId(), id, actor.employeeId()),
                            "P007 employee schedule confirmation failed");
                    current = aggregate(actor.tenantId(), id);
                }
                case "S06" -> {
                    require(action, "SUBMIT_SHIFT_CHANGE");
                    if (!actor.employeeId().equals(
                            current.record().targetEmployeeId())) {
                        throw new ProcessRejectedException(
                                "P007 only target employee may submit shift change");
                    }
                    if (command.replacementEmployeeId() != null) {
                        if (command.replacementEmployeeId()
                                .equals(current.record().targetEmployeeId())) {
                            throw new ProcessRejectedException(
                                    "P007 replacement employee must differ from target employee");
                        }
                        if (!repository.isActiveEmployeeInOrg(
                                actor.tenantId(),
                                current.record().ownerCenterId(),
                                command.replacementEmployeeId())) {
                            throw new ProcessRejectedException(
                                    "P007 replacement employee is not active in center");
                        }
                    }
                    required(
                            repository.setReplacement(
                                    actor.tenantId(),
                                    id,
                                    command.replacementEmployeeId(),
                                    actor.employeeId()),
                            "P007 shift-change submission failed");
                    current = aggregate(actor.tenantId(), id);
                }
                case "S07" -> {
                    if (!"APPROVE_CHANGE".equals(action)
                            && !"RETURN_CHANGE".equals(action)) {
                        throw new ProcessRejectedException(
                                "P007 review action invalid");
                    }
                    if ("APPROVE_CHANGE".equals(action)) {
                        ShiftRecord refreshed =
                                repository.find(actor.tenantId(), id)
                                        .orElseThrow(
                                                () ->
                                                        new ProcessRejectedException(
                                                                "P007 shift record not found"));
                        validateServerFacts(actor, refreshed);
                        required(
                                repository.markApproved(
                                        actor.tenantId(), id, actor.employeeId()),
                                "P007 change approval fact failed");
                        current = aggregate(actor.tenantId(), id);
                    }
                }
                case "S08" -> {
                    require(action, "LINK_DEPENDENCIES");
                    required(
                            repository.markDependencies(
                                    actor.tenantId(), id, actor.employeeId()),
                            "P007 attendance/catering/shuttle linkage failed");
                    current = aggregate(actor.tenantId(), id);
                }
                case "S09" -> {
                    require(action, "CLOSE_DAY");
                    required(
                            repository.markDayClosed(
                                    actor.tenantId(), id, actor.employeeId()),
                            "P007 day-close fact failed");
                    current = aggregate(actor.tenantId(), id);
                }
                default ->
                        throw new ProcessRejectedException(
                                "P007 action is not allowed from current source node");
            }

            ShiftRecord moved =
                    advance(
                            actor,
                            current.record(),
                            node,
                            action,
                            scope(key, "workflow"),
                            command.reason());
            if ("S04".equals(node)) {
                required(
                        repository.markPublished(
                                actor.tenantId(), id, actor.employeeId()),
                        "P007 schedule publication fact failed");
            }
            return aggregate(actor.tenantId(), moved.id());
        });
    }

    private void validateServerFacts(
            DatabaseSecurityContext actor, ShiftRecord record) {
        if (!repository.isActiveEmployeeInOrg(
                actor.tenantId(),
                record.ownerCenterId(),
                record.targetEmployeeId())) {
            throw new ProcessRejectedException(
                    "P007 qualification validation failed: inactive/out-of-scope employee");
        }
        if (repository.hasOverlappingShift(
                actor.tenantId(),
                record.targetEmployeeId(),
                record.startAt(),
                record.endAt(),
                record.id())) {
            throw new ProcessRejectedException("P007 schedule time conflict");
        }
        if (repository.hasAttendanceConflict(
                actor.tenantId(),
                record.targetEmployeeId(),
                record.startAt(),
                record.endAt())) {
            throw new ProcessRejectedException(
                    "P007 schedule conflicts with active leave or overtime fact");
        }

        UUID replacement = record.replacementEmployeeId();
        if (replacement != null) {
            if (replacement.equals(record.targetEmployeeId())) {
                throw new ProcessRejectedException(
                        "P007 replacement employee must differ from target employee");
            }
            if (!repository.isActiveEmployeeInOrg(
                    actor.tenantId(), record.ownerCenterId(), replacement)) {
                throw new ProcessRejectedException(
                        "P007 replacement employee is inactive or out of scope");
            }
            if (repository.hasOverlappingShift(
                    actor.tenantId(),
                    replacement,
                    record.startAt(),
                    record.endAt(),
                    record.id())) {
                throw new ProcessRejectedException(
                        "P007 replacement schedule time conflict");
            }
            if (repository.hasAttendanceConflict(
                    actor.tenantId(),
                    replacement,
                    record.startAt(),
                    record.endAt())) {
                throw new ProcessRejectedException(
                        "P007 replacement conflicts with active leave or overtime fact");
            }
        }
    }

    private ShiftRecord advance(
            DatabaseSecurityContext actor,
            ShiftRecord record,
            String node,
            String action,
            String key,
            String reason) {
        WorkflowRuntimeService.Result runtime =
                workflow.get(actor.tenantId(), record.workflowInstanceId());
        if (!node.equals(runtime.instance().currentNodeCode())
                || !node.equals(record.currentNodeCode())) {
            throw new ProcessRejectedException(
                    "P007 stale workflow projection");
        }
        if (runtime.task() == null) {
            throw new ProcessRejectedException("P007 workflow task missing");
        }
        tasks.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(),
                        runtime.task().id(),
                        actor.employeeId()));
        WorkflowRuntimeService.Result moved =
                workflow.act(
                        new WorkflowRuntimeService.ActionCommand(
                                actor.tenantId(),
                                actor.employeeId(),
                                actor.identityId(),
                                record.workflowInstanceId(),
                                runtime.task().id(),
                                node,
                                action,
                                reason,
                                key));
        Instant closed =
                "END".equals(moved.instance().currentNodeCode())
                        ? moved.instance().finishedAt()
                        : null;
        required(
                repository.moveStatus(
                        actor.tenantId(),
                        record.id(),
                        record.versionNo(),
                        label(moved.instance().currentNodeCode()),
                        closed,
                        actor.employeeId()),
                "P007 concurrent status transition conflict");
        ShiftRecord result =
                repository.find(actor.tenantId(), record.id())
                        .orElseThrow(
                                () ->
                                        new ProcessRejectedException(
                                                "P007 shift record not found"));
        emit(
                actor,
                result,
                action,
                moved.instance().currentNodeCode(),
                List.of(record.targetEmployeeId()));
        return result;
    }

    public Optional<Aggregate> find(
            DatabaseSecurityContext actor, UUID id) {
        requireActor(actor);
        return tx.required(
                actor, () -> repository.find(actor.tenantId(), id).map(Aggregate::new));
    }

    public List<Aggregate> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return tx.required(
                actor,
                () ->
                        repository.list(actor.tenantId()).stream()
                                .map(Aggregate::new)
                                .toList());
    }

    private Aggregate aggregate(UUID tenantId, UUID id) {
        return new Aggregate(
                repository.find(tenantId, id)
                        .orElseThrow(
                                () ->
                                        new ProcessRejectedException(
                                                "P007 shift record not found")));
    }

    private void emit(
            DatabaseSecurityContext actor,
            ShiftRecord record,
            String event,
            String node,
            List<UUID> recipients) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("shiftRequestId", record.id().toString());
        payload.put("businessNo", record.businessNo());
        payload.put("event", event);
        payload.put("nodeCode", node);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        outbox.enqueue(
                new TransactionalOutboxService.Command(
                        actor.tenantId(),
                        actor.employeeId(),
                        "P007_SHIFT",
                        record.id(),
                        "P007_SHIFT_EVENT",
                        1,
                        json(payload),
                        "p007:" + record.id() + ":" + record.versionNo()));
    }

    private static BigDecimal hours(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ProcessRejectedException(
                    "P007 endAt must be after startAt");
        }
        return BigDecimal.valueOf(Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
    }

    private static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P007 create command is required");
        req(command.subject(), "subject");
        req(command.changeAction(), "changeAction");
        req(command.changeReason(), "changeReason");
        req(command.periodOrCourseNo(), "periodOrCourseNo");
        if (command.targetEmployeeId() == null
                || command.ownerCenterId() == null) {
            throw new ProcessRejectedException(
                    "P007 employee and center are required");
        }
        hours(command.startAt(), command.endAt());
    }

    private static String changeAction(String raw) {
        String action = safe(raw);
        if (!CHANGE_ACTIONS.contains(action)) {
            throw new ProcessRejectedException(
                    "P007 changeAction is not source-backed");
        }
        return action;
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null
                || actor.tenantId() == null
                || actor.employeeId() == null
                || actor.identityId() == null
                || actor.orgId() == null
                || actor.userId() == null
                || actor.positionId() == null) {
            throw new ProcessRejectedException(
                    "P007 authenticated employee context required");
        }
    }

    private static void required(int updated, String message) {
        if (updated != 1) {
            throw new ProcessRejectedException(message);
        }
    }

    private static void require(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new ProcessRejectedException(
                    "P007 action not allowed from current source node");
        }
    }

    private static void req(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProcessRejectedException(
                    "P007 required field missing: " + field);
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,32}") ? normalized : "";
    }

    private static String scope(String key, String suffix) {
        if (key == null || key.isBlank()) {
            throw new ProcessRejectedException(
                    "P007 idempotency key required");
        }
        String scoped = key + ":" + suffix;
        if (scoped.length() > 128) {
            throw new ProcessRejectedException(
                    "P007 idempotency key too long");
        }
        return scoped;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static WorkflowFormService.FieldValue text(
            String code, String value) {
        return new WorkflowFormService.FieldValue(
                code,
                "TEXT",
                value,
                null,
                null,
                null,
                null,
                null,
                "P1",
                false);
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode result = mapper.createArrayNode();
        ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(id -> result.add(id.toString()));
        return result;
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new ProcessRejectedException(
                    "P007 JSON serialization failed", exception);
        }
    }

    public static String label(String node) {
        return switch (node) {
            case "S01" -> "业务量与活动需求输入";
            case "S02" -> "班次模板匹配";
            case "S03" -> "资格与连续工时校验";
            case "S04" -> "主管发布排班";
            case "S05" -> "员工确认";
            case "S06" -> "换班替班申请";
            case "S07" -> "变更审批";
            case "S08" -> "考勤与餐饮班车联动";
            case "S09" -> "日结";
            case "END" -> "已关闭";
            default ->
                    throw new ProcessRejectedException(
                            "P007 unknown workflow node: " + node);
        };
    }

    public interface Repository {
        Optional<UUID> workflowVersion(UUID tenantId);

        Optional<FormRef> form(UUID tenantId);

        List<UUID> permissionCandidates(
                UUID tenantId, String permission, UUID orgId);

        boolean isActiveEmployeeInOrg(
                UUID tenantId, UUID orgId, UUID employeeId);

        boolean hasOverlappingShift(
                UUID tenantId,
                UUID employeeId,
                Instant start,
                Instant end,
                UUID excludeId);

        boolean hasAttendanceConflict(
                UUID tenantId, UUID employeeId, Instant start, Instant end);

        void insert(ShiftRecord record, UUID actor);

        int bindAndMove(
                UUID tenantId,
                UUID id,
                int version,
                UUID workflowId,
                String status,
                UUID actor);

        int moveStatus(
                UUID tenantId,
                UUID id,
                int version,
                String status,
                Instant closedAt,
                UUID actor);

        int markValidated(
                UUID tenantId,
                UUID id,
                BigDecimal continuousHours,
                UUID actor);

        int markPublished(UUID tenantId, UUID id, UUID actor);

        int markConfirmed(UUID tenantId, UUID id, UUID actor);

        int setReplacement(
                UUID tenantId, UUID id, UUID replacement, UUID actor);

        int markApproved(UUID tenantId, UUID id, UUID actor);

        int markDependencies(UUID tenantId, UUID id, UUID actor);

        int markDayClosed(UUID tenantId, UUID id, UUID actor);

        Optional<ShiftRecord> find(UUID tenantId, UUID id);

        List<ShiftRecord> list(UUID tenantId);
    }

    public record FormRef(UUID id, int versionNo) {}

    public record CreateCommand(
            String subject,
            String reason,
            UUID ownerCenterId,
            UUID targetEmployeeId,
            String changeAction,
            String changeReason,
            String templateCode,
            String periodOrCourseNo,
            Instant startAt,
            Instant endAt) {}

    public record ActionCommand(
            int expectedVersion,
            UUID replacementEmployeeId,
            String reason) {}

    public record ShiftRecord(
            UUID id,
            UUID tenantId,
            String businessNo,
            UUID workflowInstanceId,
            String workflowInstanceNo,
            String currentNodeCode,
            String status,
            int versionNo,
            String subject,
            String reason,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            UUID targetEmployeeId,
            UUID replacementEmployeeId,
            String changeAction,
            String changeReason,
            String templateCode,
            String periodOrCourseNo,
            Instant startAt,
            Instant endAt,
            BigDecimal durationHours,
            Instant qualificationCheckedAt,
            BigDecimal continuousWorkHours,
            Instant conflictCheckedAt,
            Instant publishedAt,
            Instant employeeConfirmedAt,
            Instant approvedAt,
            Instant attendanceLinkedAt,
            Instant cateringLinkedAt,
            Instant shuttleLinkedAt,
            Instant dayClosedAt,
            Instant updatedAt) {}

    public record Aggregate(ShiftRecord record) {
        public Aggregate metadataOnly() {
            ShiftRecord source = record;
            return new Aggregate(
                    new ShiftRecord(
                            source.id(),
                            source.tenantId(),
                            source.businessNo(),
                            source.workflowInstanceId(),
                            source.workflowInstanceNo(),
                            source.currentNodeCode(),
                            source.status(),
                            source.versionNo(),
                            null,
                            null,
                            source.ownerCenterId(),
                            null,
                            null,
                            null,
                            source.changeAction(),
                            null,
                            null,
                            source.periodOrCourseNo(),
                            source.startAt(),
                            source.endAt(),
                            source.durationHours(),
                            source.qualificationCheckedAt(),
                            source.continuousWorkHours(),
                            source.conflictCheckedAt(),
                            source.publishedAt(),
                            source.employeeConfirmedAt(),
                            source.approvedAt(),
                            source.attendanceLinkedAt(),
                            source.cateringLinkedAt(),
                            source.shuttleLinkedAt(),
                            source.dayClosedAt(),
                            source.updatedAt()));
        }
    }
}
