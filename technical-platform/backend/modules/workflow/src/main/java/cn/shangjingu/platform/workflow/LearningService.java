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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class LearningService {
    public static final String PROCESS_CODE = "P010";
    public static final String MANAGE = "p010.learning.manage";
    public static final String CERTIFY = "p010.learning.certify";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TenantTransactionRunner tx;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService tasks;
    private final WorkflowFormService forms;
    private final Repository repo;
    private final ObjectMapper mapper;

    @Autowired
    public LearningService(
            TenantTransactionRunner tx,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService tasks,
            WorkflowFormService forms,
            Repository repo,
            ObjectMapper mapper) {
        this.tx = tx;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.tasks = tasks;
        this.forms = forms;
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Compatibility constructor retained for action-only unit tests. */
    public LearningService(
            TenantTransactionRunner tx,
            IdempotencyRegistry idempotency,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService tasks,
            WorkflowFormService forms,
            Repository repo,
            ObjectMapper mapper) {
        this(tx, idempotency, null, outbox, workflow, tasks, forms, repo, mapper);
    }

    public Aggregate create(
            DatabaseSecurityContext actor,
            String key,
            String requestHash,
            CreateCommand command) {
        actor(actor);
        validateCreate(command);
        if (!actor.orgId().equals(command.ownerCenterId())) {
            throw reject("owner center must equal authenticated center");
        }
        return tx.required(actor, () -> {
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    scope(key, "create"),
                    requestHash,
                    "learning.learning_assignment",
                    UUID.randomUUID(),
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return aggregate(required(actor.tenantId(), claim.resourceId()));
            }
            if (numbers == null) {
                throw reject("business number service missing");
            }
            if (!repo.activeEmployeeInCenter(
                    actor.tenantId(), command.ownerEmployeeId(), command.ownerCenterId())) {
                throw reject("target employee is not active in owner center");
            }
            if (repo.workflowVersion(actor.tenantId()).isEmpty()) {
                throw reject("workflow not published");
            }
            if (repo.form(actor.tenantId()).isEmpty()) {
                throw reject("form not published");
            }
            LearningRecord record = new LearningRecord(
                    claim.resourceId(),
                    actor.tenantId(),
                    numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE),
                    null,
                    null,
                    "S01",
                    label("S01"),
                    0,
                    command.subject().trim(),
                    command.ownerCenterId(),
                    command.ownerEmployeeId(),
                    command.contentVersion().trim(),
                    command.courseVersionId().trim(),
                    command.periodOrCourseNo().trim(),
                    BigDecimal.ZERO,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.now());
            repo.insert(
                    record,
                    trim(command.reason()),
                    command.courseTeamName().trim(),
                    risk(command.riskLevel()),
                    trim(command.learnerProfile()),
                    command.plannedStartAt(),
                    command.plannedFinishAt(),
                    actor.employeeId());
            emit(actor, record.id(), "ASSIGNMENT_CREATED");
            return aggregate(required(actor.tenantId(), record.id()));
        });
    }

    public Optional<Aggregate> find(DatabaseSecurityContext actor, UUID id) {
        actor(actor);
        return tx.required(actor, () -> repo.find(actor.tenantId(), id).map(this::aggregate));
    }

    public List<Aggregate> list(DatabaseSecurityContext actor) {
        actor(actor);
        return tx.required(
                actor,
                () -> repo.list(actor.tenantId()).stream().map(this::aggregate).toList());
    }

    public Aggregate progress(
            DatabaseSecurityContext actor,
            UUID id,
            String key,
            String hash,
            ProgressCommand command) {
        actor(actor);
        Objects.requireNonNull(command);
        if (command.completionRate() == null
                || command.completionRate().compareTo(BigDecimal.ZERO) < 0
                || command.completionRate().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw reject("completionRate must be 0-100");
        }
        return tx.required(actor, () -> {
            LearningRecord record = required(actor.tenantId(), id);
            self(actor, record);
            node(record, "S03");
            if (claim(actor, key, hash, "progress", id)) {
                return aggregate(record);
            }
            repo.appendEvidence(
                    actor.tenantId(),
                    id,
                    "LEARNING_PROGRESS",
                    actor.employeeId(),
                    null,
                    command.completionRate(),
                    null,
                    command.note(),
                    null);
            repo.updateProgress(actor.tenantId(), id, command.completionRate(), actor.employeeId());
            if (command.completionRate().compareTo(BigDecimal.valueOf(100)) == 0) {
                repo.markLearningCompleted(actor.tenantId(), id, actor.employeeId());
                advance(
                        actor,
                        required(actor.tenantId(), id),
                        "S03",
                        "COMPLETE_LEARNING",
                        scope(key, "workflow"),
                        command.note());
            }
            emit(actor, id, "LEARNING_PROGRESS");
            return aggregate(required(actor.tenantId(), id));
        });
    }

    public Aggregate exam(
            DatabaseSecurityContext actor,
            UUID id,
            String key,
            String hash,
            ExamCommand command) {
        actor(actor);
        Objects.requireNonNull(command);
        if (command.score1000() < 0 || command.score1000() > 1000) {
            throw reject("score1000 must be 0-1000");
        }
        return tx.required(actor, () -> {
            LearningRecord record = required(actor.tenantId(), id);
            self(actor, record);
            node(record, "S04");
            if (claim(actor, key, hash, "exam", id)) {
                return aggregate(record);
            }
            repo.appendEvidence(
                    actor.tenantId(),
                    id,
                    "EXAM_ATTEMPT",
                    actor.employeeId(),
                    command.score1000(),
                    null,
                    null,
                    command.note(),
                    null);
            repo.updateExam(actor.tenantId(), id, command.score1000(), actor.employeeId());
            advance(
                    actor,
                    required(actor.tenantId(), id),
                    "S04",
                    "RECORD_EXAM",
                    scope(key, "workflow"),
                    command.note());
            emit(actor, id, "EXAM_RECORDED");
            return aggregate(required(actor.tenantId(), id));
        });
    }

    public Aggregate practical(
            DatabaseSecurityContext actor,
            UUID id,
            String key,
            String hash,
            PracticalCommand command) {
        actor(actor);
        Objects.requireNonNull(command);
        String result = practical(command.result());
        return tx.required(actor, () -> {
            LearningRecord record = required(actor.tenantId(), id);
            self(actor, record);
            node(record, "S05");
            if (claim(actor, key, hash, "practical", id)) {
                return aggregate(record);
            }
            repo.appendEvidence(
                    actor.tenantId(),
                    id,
                    "PRACTICAL_ASSESSMENT",
                    actor.employeeId(),
                    null,
                    null,
                    result,
                    command.note(),
                    null);
            repo.updatePractical(actor.tenantId(), id, result, actor.employeeId());
            advance(
                    actor,
                    required(actor.tenantId(), id),
                    "S05",
                    "RECORD_PRACTICAL",
                    scope(key, "workflow"),
                    command.note());
            emit(actor, id, "PRACTICAL_RECORDED");
            return aggregate(required(actor.tenantId(), id));
        });
    }

    public Aggregate action(
            DatabaseSecurityContext actor,
            UUID id,
            String action,
            String key,
            String hash,
            ActionCommand command) {
        actor(actor);
        Objects.requireNonNull(command);
        String normalizedAction = safe(action);
        return tx.required(actor, () -> {
            LearningRecord record = required(actor.tenantId(), id);
            if (record.versionNo() != command.expectedVersion()) {
                throw reject("version conflict");
            }
            if (claim(
                    actor,
                    key,
                    hash,
                    "action." + normalizedAction.toLowerCase(Locale.ROOT),
                    id)) {
                return aggregate(record);
            }
            switch (record.currentNodeCode()) {
                case "S01" -> {
                    must(normalizedAction, "PUBLISH_CONTENT");
                    start(actor, record, key, command.note());
                    repo.markContentPublished(actor.tenantId(), id, actor.employeeId());
                }
                case "S02" -> {
                    must(normalizedAction, "ASSIGN_BY_RISK");
                    repo.markRiskAssigned(actor.tenantId(), id, actor.employeeId());
                    advance(
                            actor,
                            record,
                            "S02",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                case "S06" -> {
                    if (!normalizedAction.equals("CERTIFY")
                            && !normalizedAction.equals("RETURN_FOR_TRAINING")) {
                        throw reject("certification action invalid");
                    }
                    repo.appendEvidence(
                            actor.tenantId(),
                            id,
                            "PROFESSIONAL_CERTIFICATION",
                            actor.employeeId(),
                            null,
                            null,
                            normalizedAction,
                            command.note(),
                            null);
                    if (normalizedAction.equals("CERTIFY")) {
                        repo.markCertified(actor.tenantId(), id, actor.employeeId());
                    }
                    advance(
                            actor,
                            record,
                            "S06",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                case "S07" -> {
                    must(normalizedAction, "ACTIVATE_QUALIFICATION");
                    if (command.effectiveDate() == null) {
                        throw reject("effectiveDate is required");
                    }
                    if (command.expireDate() != null
                            && command.expireDate().isBefore(command.effectiveDate())) {
                        throw reject("expireDate before effectiveDate");
                    }
                    repo.activateQualification(
                            actor.tenantId(),
                            id,
                            command.effectiveDate(),
                            command.expireDate(),
                            actor.employeeId());
                    repo.appendEvidence(
                            actor.tenantId(),
                            id,
                            "QUALIFICATION_ACTIVATION",
                            actor.employeeId(),
                            null,
                            null,
                            null,
                            command.note(),
                            jsonDates(command.effectiveDate(), command.expireDate()));
                    advance(
                            actor,
                            required(actor.tenantId(), id),
                            "S07",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                case "S08" -> {
                    must(normalizedAction, "LINK_PERMISSIONS");
                    List<UUID> roles =
                            repo.linkPermissions(actor.tenantId(), id, actor.employeeId());
                    if (roles.isEmpty()) {
                        throw reject("qualification permission binding is missing; fail closed");
                    }
                    repo.appendEvidence(
                            actor.tenantId(),
                            id,
                            "PERMISSION_LINK",
                            actor.employeeId(),
                            null,
                            null,
                            null,
                            command.note(),
                            jsonRoles(roles));
                    repo.markPermissionLinked(actor.tenantId(), id, actor.employeeId());
                    advance(
                            actor,
                            required(actor.tenantId(), id),
                            "S08",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                case "S09" -> {
                    must(normalizedAction, "COMPLETE_RETRAINING_CHECK");
                    repo.markRetrainingChecked(actor.tenantId(), id, actor.employeeId());
                    repo.appendEvidence(
                            actor.tenantId(),
                            id,
                            "RETRAINING_CHECK",
                            actor.employeeId(),
                            null,
                            null,
                            null,
                            command.note(),
                            null);
                    advance(
                            actor,
                            required(actor.tenantId(), id),
                            "S09",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                case "S10" -> {
                    must(normalizedAction, "ARCHIVE");
                    repo.markArchived(actor.tenantId(), id, actor.employeeId());
                    advance(
                            actor,
                            required(actor.tenantId(), id),
                            "S10",
                            normalizedAction,
                            scope(key, "workflow"),
                            command.note());
                }
                default -> throw reject("action not allowed from current node");
            }
            emit(actor, id, normalizedAction);
            return aggregate(required(actor.tenantId(), id));
        });
    }

    private void start(
            DatabaseSecurityContext actor,
            LearningRecord record,
            String key,
            String note) {
        if (record.workflowInstanceId() != null) {
            throw reject("workflow already started");
        }
        if (record.ownerCenterId() == null || record.ownerEmployeeId() == null) {
            throw reject("assignment owner center/employee missing");
        }
        UUID version =
                repo.workflowVersion(actor.tenantId())
                        .orElseThrow(() -> reject("workflow not published"));
        FormRef form = repo.form(actor.tenantId()).orElseThrow(() -> reject("form not published"));
        List<UUID> managers =
                repo.permissionCandidates(actor.tenantId(), MANAGE, record.ownerCenterId());
        List<UUID> certifiers =
                repo.permissionCandidates(actor.tenantId(), CERTIFY, record.ownerCenterId());
        if (managers.isEmpty() || certifiers.isEmpty()) {
            throw reject("manager/certifier candidates missing");
        }
        ObjectNode context = mapper.createObjectNode();
        context.put("ownerCenterId", record.ownerCenterId().toString());
        context.set("targetEmployeeIds", uuidArray(List.of(record.ownerEmployeeId())));
        context.set("managerCandidateIds", uuidArray(managers));
        context.set("certifierCandidateIds", uuidArray(certifiers));
        WorkflowRuntimeService.Result started =
                workflow.start(
                        new WorkflowRuntimeService.StartCommand(
                                actor.tenantId(),
                                actor.employeeId(),
                                actor.identityId(),
                                version,
                                "learning.learning_assignment",
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
                                text("course_version_id", record.courseVersionId()),
                                text("content_version", record.contentVersion()),
                                text("period_or_course_no", record.periodOrCourseNo()),
                                text("owner_employee_id", record.ownerEmployeeId().toString())),
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
                                "PUBLISH_CONTENT",
                                note,
                                scope(key, "publish")));
        if (repo.bindWorkflow(
                        actor.tenantId(),
                        record.id(),
                        record.versionNo(),
                        moved.instance().id(),
                        "S02",
                        label("S02"),
                        actor.employeeId())
                != 1) {
            throw reject("concurrent publish conflict");
        }
    }

    private void advance(
            DatabaseSecurityContext actor,
            LearningRecord record,
            String source,
            String action,
            String key,
            String note) {
        WorkflowRuntimeService.Result runtime =
                workflow.get(actor.tenantId(), record.workflowInstanceId());
        if (!source.equals(runtime.instance().currentNodeCode())
                || !source.equals(record.currentNodeCode())) {
            throw reject("stale workflow projection");
        }
        if (runtime.task() == null) {
            throw reject("workflow task missing");
        }
        tasks.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(
                        actor.tenantId(), runtime.task().id(), actor.employeeId()));
        WorkflowRuntimeService.Result moved =
                workflow.act(
                        new WorkflowRuntimeService.ActionCommand(
                                actor.tenantId(),
                                actor.employeeId(),
                                actor.identityId(),
                                record.workflowInstanceId(),
                                runtime.task().id(),
                                source,
                                action,
                                note,
                                key));
        Instant closed =
                "END".equals(moved.instance().currentNodeCode())
                        ? moved.instance().finishedAt()
                        : null;
        if (repo.moveNode(
                        actor.tenantId(),
                        record.id(),
                        record.versionNo(),
                        moved.instance().currentNodeCode(),
                        label(moved.instance().currentNodeCode()),
                        closed,
                        actor.employeeId())
                != 1) {
            throw reject("concurrent transition conflict");
        }
    }

    private Aggregate aggregate(LearningRecord record) {
        return new Aggregate(record, repo.evidence(record.tenantId(), record.id()));
    }

    private LearningRecord required(UUID tenantId, UUID id) {
        return repo.find(tenantId, id).orElseThrow(() -> reject("assignment not found"));
    }

    private boolean claim(
            DatabaseSecurityContext actor,
            String key,
            String hash,
            String type,
            UUID id) {
        return idempotency.claim(
                        actor.tenantId(),
                        actor.employeeId(),
                        key,
                        hash,
                        "learning.learning_assignment." + type,
                        id,
                        IDEMPOTENCY_TTL)
                .existing();
    }

    private void emit(DatabaseSecurityContext actor, UUID id, String event) {
        LearningRecord record = required(actor.tenantId(), id);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("assignmentId", id.toString());
        payload.put("businessNo", record.businessNo());
        payload.put("event", event);
        payload.put("nodeCode", record.currentNodeCode());
        outbox.enqueue(
                new TransactionalOutboxService.Command(
                        actor.tenantId(),
                        actor.employeeId(),
                        "P010_LEARNING",
                        id,
                        "P010_LEARNING_EVENT",
                        1,
                        json(payload),
                        "p010:" + id + ":" + record.versionNo()));
    }

    private static void validateCreate(CreateCommand command) {
        Objects.requireNonNull(command, "P010 create command is required");
        required(command.subject(), "subject");
        required(command.contentVersion(), "contentVersion");
        required(command.courseTeamName(), "courseTeamName");
        required(command.courseVersionId(), "courseVersionId");
        required(command.periodOrCourseNo(), "periodOrCourseNo");
        if (command.ownerCenterId() == null) {
            throw reject("ownerCenterId is required");
        }
        if (command.ownerEmployeeId() == null) {
            throw reject("ownerEmployeeId is required");
        }
        max(command.subject(), 255, "subject");
        max(command.reason(), 4000, "reason");
        max(command.contentVersion(), 32, "contentVersion");
        max(command.courseTeamName(), 200, "courseTeamName");
        max(command.courseVersionId(), 64, "courseVersionId");
        max(command.learnerProfile(), 500, "learnerProfile");
        max(command.periodOrCourseNo(), 32, "periodOrCourseNo");
        risk(command.riskLevel());
        if ((command.plannedStartAt() == null) != (command.plannedFinishAt() == null)) {
            throw reject("plannedStartAt and plannedFinishAt must be supplied together");
        }
        if (command.plannedStartAt() != null
                && !command.plannedFinishAt().isAfter(command.plannedStartAt())) {
            throw reject("plannedFinishAt must be after plannedStartAt");
        }
    }

    private static void actor(DatabaseSecurityContext actor) {
        if (actor == null
                || actor.tenantId() == null
                || actor.employeeId() == null
                || actor.identityId() == null
                || actor.userId() == null
                || actor.orgId() == null
                || actor.positionId() == null) {
            throw reject("authenticated employee context required");
        }
    }

    private static void self(DatabaseSecurityContext actor, LearningRecord record) {
        if (!actor.employeeId().equals(record.ownerEmployeeId())) {
            throw reject("employee action is self-only");
        }
    }

    private static void node(LearningRecord record, String node) {
        if (!node.equals(record.currentNodeCode())) {
            throw reject("operation not allowed from current node");
        }
    }

    private static String practical(String value) {
        if (value == null
                || !List.of("通过", "补训", "不通过", "不适用").contains(value.trim())) {
            throw reject("practical result invalid");
        }
        return value.trim();
    }

    private static String safe(String value) {
        if (value == null) {
            return "INVALID";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,40}") ? normalized : "INVALID";
    }

    private static String risk(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? "NORMAL"
                        : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "NORMAL", "HIGH", "CRITICAL").contains(normalized)) {
            throw reject("riskLevel invalid");
        }
        return normalized;
    }

    private static void must(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw reject("action not allowed from current node");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw reject("required field missing: " + field);
        }
    }

    private static void max(String value, int length, String field) {
        if (value != null && value.trim().length() > length) {
            throw reject(field + " exceeds " + length + " characters");
        }
    }

    private static ProcessRejectedException reject(String message) {
        return new ProcessRejectedException("P010 " + message);
    }

    private static String scope(String key, String suffix) {
        if (key == null || key.isBlank()) {
            throw reject("idempotency key required");
        }
        String scoped = key + ":" + suffix;
        if (scoped.length() > 128) {
            throw reject("idempotency key too long");
        }
        return scoped;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static WorkflowFormService.FieldValue text(String code, String value) {
        return new WorkflowFormService.FieldValue(
                code, "TEXT", value, null, null, null, null, null, "P1", false);
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode array = mapper.createArrayNode();
        ids.stream().filter(Objects::nonNull).distinct().forEach(id -> array.add(id.toString()));
        return array;
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw reject("JSON serialization failed");
        }
    }

    private JsonNode jsonDates(LocalDate effectiveDate, LocalDate expireDate) {
        ObjectNode node = mapper.createObjectNode();
        node.put("effectiveDate", effectiveDate.toString());
        if (expireDate != null) {
            node.put("expireDate", expireDate.toString());
        }
        return node;
    }

    private JsonNode jsonRoles(List<UUID> roles) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode array = node.putArray("roleIds");
        roles.forEach(role -> array.add(role.toString()));
        return node;
    }

    public static String label(String node) {
        return switch (node) {
            case "S01" -> "课程/制度版本发布";
            case "S02" -> "按岗位风险指派";
            case "S03" -> "员工学习";
            case "S04" -> "1000分制考试";
            case "S05" -> "线下实操";
            case "S06" -> "主管/专业人员认证";
            case "S07" -> "资格生效";
            case "S08" -> "岗位权限联动";
            case "S09" -> "到期复训/复证";
            case "S10" -> "归档";
            case "END" -> "已关闭";
            default -> throw reject("unknown node " + node);
        };
    }

    public interface Repository {
        Optional<UUID> workflowVersion(UUID tenantId);
        Optional<FormRef> form(UUID tenantId);
        List<UUID> permissionCandidates(UUID tenantId, String permission, UUID orgId);
        boolean activeEmployeeInCenter(UUID tenantId, UUID employeeId, UUID orgId);
        void insert(
                LearningRecord record,
                String reason,
                String courseTeamName,
                String riskLevel,
                String learnerProfile,
                Instant plannedStartAt,
                Instant plannedFinishAt,
                UUID actor);
        Optional<LearningRecord> find(UUID tenantId, UUID id);
        List<LearningRecord> list(UUID tenantId);
        List<Evidence> evidence(UUID tenantId, UUID id);
        int bindWorkflow(
                UUID tenantId,
                UUID id,
                int version,
                UUID workflowId,
                String node,
                String status,
                UUID actor);
        int moveNode(
                UUID tenantId,
                UUID id,
                int version,
                String node,
                String status,
                Instant closed,
                UUID actor);
        void appendEvidence(
                UUID tenantId,
                UUID id,
                String type,
                UUID actor,
                Long score,
                BigDecimal progress,
                String practical,
                String text,
                JsonNode json);
        int updateProgress(UUID tenantId, UUID id, BigDecimal progress, UUID actor);
        int markLearningCompleted(UUID tenantId, UUID id, UUID actor);
        int updateExam(UUID tenantId, UUID id, long score, UUID actor);
        int updatePractical(UUID tenantId, UUID id, String result, UUID actor);
        int markContentPublished(UUID tenantId, UUID id, UUID actor);
        int markRiskAssigned(UUID tenantId, UUID id, UUID actor);
        int markCertified(UUID tenantId, UUID id, UUID actor);
        int activateQualification(
                UUID tenantId, UUID id, LocalDate effective, LocalDate expire, UUID actor);
        List<UUID> linkPermissions(UUID tenantId, UUID id, UUID actor);
        int markPermissionLinked(UUID tenantId, UUID id, UUID actor);
        int markRetrainingChecked(UUID tenantId, UUID id, UUID actor);
        int markArchived(UUID tenantId, UUID id, UUID actor);
    }

    public record FormRef(UUID id, int versionNo) {}
    public record CreateCommand(
            String subject,
            String reason,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            String contentVersion,
            String courseTeamName,
            String courseVersionId,
            String learnerProfile,
            String periodOrCourseNo,
            String riskLevel,
            Instant plannedStartAt,
            Instant plannedFinishAt) {}
    public record ProgressCommand(BigDecimal completionRate, String note) {}
    public record ExamCommand(long score1000, String note) {}
    public record PracticalCommand(String result, String note) {}
    public record ActionCommand(
            int expectedVersion,
            String note,
            LocalDate effectiveDate,
            LocalDate expireDate) {}
    public record LearningRecord(
            UUID id,
            UUID tenantId,
            String businessNo,
            UUID workflowInstanceId,
            String workflowInstanceNo,
            String currentNodeCode,
            String status,
            int versionNo,
            String subject,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            String contentVersion,
            String courseVersionId,
            String periodOrCourseNo,
            BigDecimal completionRate,
            Long score1000,
            String practicalResult,
            LocalDate qualificationEffectiveDate,
            LocalDate qualificationExpireDate,
            Instant certifiedAt,
            UUID certifiedBy,
            Instant permissionLinkedAt,
            Instant archivedAt,
            Instant updatedAt) {}
    public record Evidence(
            UUID id,
            String evidenceType,
            UUID actorEmployeeId,
            Long score1000,
            BigDecimal completionRate,
            String practicalResult,
            String evidenceText,
            JsonNode evidenceJson,
            Instant createdAt) {}
    public record Aggregate(LearningRecord record, List<Evidence> evidence) {
        public Aggregate metadataOnly() {
            LearningRecord source = record;
            return new Aggregate(
                    new LearningRecord(
                            source.id(),
                            source.tenantId(),
                            source.businessNo(),
                            source.workflowInstanceId(),
                            source.workflowInstanceNo(),
                            source.currentNodeCode(),
                            source.status(),
                            source.versionNo(),
                            null,
                            source.ownerCenterId(),
                            null,
                            source.contentVersion(),
                            source.courseVersionId(),
                            source.periodOrCourseNo(),
                            null,
                            null,
                            null,
                            null,
                            source.qualificationExpireDate(),
                            null,
                            null,
                            source.permissionLinkedAt(),
                            source.archivedAt(),
                            source.updatedAt()),
                    List.of());
        }
    }
}
