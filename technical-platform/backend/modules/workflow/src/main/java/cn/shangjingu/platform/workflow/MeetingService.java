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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** PHASE-10 / P006 canonical meeting and action-item lifecycle. */
@Service
public class MeetingService {
    public static final String PROCESS_CODE = "P006";
    public static final String INITIAL_FORM_CODE = "CTR-P006-F01";
    public static final String EVENT_TYPE = "P006_MEETING_EVENT";
    public static final String AGGREGATE_TYPE = "P006_MEETING";
    public static final String MANAGE_PERMISSION = "p006.meeting.manage";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Set<String> VISIBILITY_LEVELS = Set.of("公开", "内部", "秘密", "机密");

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService taskAssignment;
    private final WorkflowFormService forms;
    private final Repository repository;
    private final ObjectMapper mapper;

    public MeetingService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            TransactionalOutboxService outbox,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService taskAssignment,
            WorkflowFormService forms,
            Repository repository,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.outbox = outbox;
        this.workflow = workflow;
        this.taskAssignment = taskAssignment;
        this.forms = forms;
        this.repository = repository;
        this.mapper = mapper;
    }

    public MeetingAggregate create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        requireActor(actor);
        validateCreate(command);
        if (!actor.orgId().equals(command.ownerCenterId())) {
            throw new ProcessRejectedException("P006 meeting owner center must equal authenticated center");
        }
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    "collaboration.meeting",
                    proposedId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) return aggregate(actor.tenantId(), claim.resourceId());

            UUID workflowVersion = repository
                    .latestPublishedWorkflowVersion(actor.tenantId(), PROCESS_CODE)
                    .orElseThrow(
                            () -> new ProcessRejectedException("P006 published workflow version is not configured"));
            FormRef form = repository
                    .latestPublishedForm(actor.tenantId(), INITIAL_FORM_CODE, PROCESS_CODE, "S01")
                    .orElseThrow(() -> new ProcessRejectedException("P006 initial published form is not configured"));
            List<UUID> managers = repository.permissionCandidates(actor.tenantId(), MANAGE_PERMISSION, actor.orgId());
            if (!managers.contains(actor.employeeId())) {
                throw new ProcessRejectedException("P006 creator must remain an eligible center manager candidate");
            }
            List<UUID> participants = command.participantEmployeeIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (participants.isEmpty()) throw new ProcessRejectedException("P006 participant list is required");
            if (!repository.areActiveEmployeesInOrg(actor.tenantId(), actor.orgId(), participants)) {
                throw new ProcessRejectedException(
                        "P006 participants must be active employees in the authenticated center");
            }

            Instant now = Instant.now();
            Meeting meeting = new Meeting(
                    claim.resourceId(),
                    actor.tenantId(),
                    numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE),
                    null,
                    null,
                    "S01",
                    label("S01"),
                    0,
                    command.officialSubject().trim(),
                    command.officialType().trim(),
                    command.officialContent().trim(),
                    command.attendanceType().trim(),
                    "P006_MEETING",
                    actor.employeeId().toString(),
                    command.visibilityLevel().trim(),
                    trimToNull(command.venueChannel()),
                    command.ownerCenterId(),
                    actor.employeeId(),
                    command.businessDate() == null ? LocalDate.now() : command.businessDate(),
                    command.startAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    now);
            repository.insertMeeting(meeting, actor.employeeId());
            repository.replaceAgenda(actor.tenantId(), meeting.id(), command.agendaItems(), actor.employeeId());
            repository.insertParticipants(actor.tenantId(), meeting.id(), participants, actor.employeeId());

            ObjectNode context = mapper.createObjectNode();
            context.put("ownerEmployeeId", actor.employeeId().toString());
            context.put("ownerCenterId", actor.orgId().toString());
            context.set("managerCandidateIds", uuidArray(managers));
            context.set("participantEmployeeIds", uuidArray(participants));
            WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                    actor.tenantId(),
                    actor.employeeId(),
                    actor.identityId(),
                    workflowVersion,
                    "collaboration.meeting",
                    meeting.id(),
                    meeting.businessNo(),
                    meeting.officialSubject(),
                    "NORMAL",
                    context,
                    scopedKey(idempotencyKey, "start")));
            forms.submit(new WorkflowFormService.SubmitForm(
                    actor.tenantId(),
                    actor.employeeId(),
                    actor.identityId(),
                    started.instance().id(),
                    null,
                    form.id(),
                    form.versionNo(),
                    initialFormValues(started.instance(), meeting),
                    scopedKey(idempotencyKey, "form")));
            WorkflowRuntimeService.Result submitted = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(),
                    actor.employeeId(),
                    actor.identityId(),
                    started.instance().id(),
                    null,
                    "S01",
                    "SUBMIT_AGENDA",
                    null,
                    scopedKey(idempotencyKey, "s01")));
            if (repository.bindWorkflowAndMove(
                            actor.tenantId(),
                            meeting.id(),
                            0,
                            submitted.instance().id(),
                            label("S02"),
                            actor.employeeId())
                    != 1) {
                throw new ProcessRejectedException("P006 concurrent create transition conflict");
            }
            Meeting created = requiredMeeting(actor.tenantId(), meeting.id());
            emit(actor, created, "SUBMIT_AGENDA", "S02", participants);
            return aggregate(actor.tenantId(), meeting.id());
        });
    }

    public MeetingAggregate act(
            DatabaseSecurityContext actor,
            UUID meetingId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P006 action command is required");
        String action = safeAction(actionCode);
        if (action.isBlank()) throw new ProcessRejectedException("P006 action code is invalid");
        return transactions.required(actor, () -> {
            MeetingAggregate current = aggregate(actor.tenantId(), meetingId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.employeeId(),
                    idempotencyKey,
                    requestHash,
                    "collaboration.meeting.action." + action.toLowerCase(Locale.ROOT),
                    meetingId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) return current;
            if (current.meeting().versionNo() != command.expectedVersion()) {
                throw new ProcessRejectedException("P006 meeting version conflict");
            }
            String node = current.meeting().currentNodeCode();
            if (node == null) throw new ProcessRejectedException("P006 workflow has no current node");

            if ("S04".equals(node) && ("ATTEND".equals(action) || "LEAVE".equals(action))) {
                return attendanceReceipt(actor, current, action, idempotencyKey);
            }
            if ("S08".equals(node) && "SUBMIT_ACTION_EVIDENCE".equals(action)) {
                return actionEvidence(actor, current, command, idempotencyKey);
            }

            switch (node) {
                case "S02" -> requireAction(action, "CONFIRM_MATERIALS");
                case "S03" -> requireAction(action, "PUBLISH_MEETING");
                case "S05" -> requireAction(action, "COMPLETE_MEETING");
                case "S06" -> {
                    requireAction(action, "CONFIRM_MINUTES");
                    requireText(command.minutesText(), "minutesText");
                    if (repository.confirmMinutes(
                                    actor.tenantId(),
                                    meetingId,
                                    current.meeting().versionNo(),
                                    command.minutesText().trim(),
                                    actor.employeeId())
                            != 1) {
                        throw new ProcessRejectedException("P006 concurrent minutes confirmation conflict");
                    }
                    current = aggregate(actor.tenantId(), meetingId);
                }
                case "S07" -> {
                    requireAction(action, "GENERATE_ACTION_ITEMS");
                    if (current.meeting().minutesConfirmedAt() == null) {
                        throw new ProcessRejectedException("P006 confirmed minutes are required before action items");
                    }
                    validateActionItems(current, command.actionItems());
                    repository.replaceActionItems(
                            actor.tenantId(), meetingId, command.actionItems(), actor.employeeId());
                    current = aggregate(actor.tenantId(), meetingId);
                }
                case "S09" -> {
                    if ("RETURN_ACTIONS".equals(action)) {
                        List<UUID> ids = requiredItemIds(command.actionItemIds());
                        if (repository.returnActionItems(actor.tenantId(), meetingId, ids, actor.employeeId())
                                != ids.size()) {
                            throw new ProcessRejectedException("P006 rework update conflict or invalid action item");
                        }
                    } else {
                        requireAction(action, "ACCEPT_ACTIONS");
                        if (current.actionItems().isEmpty()
                                || current.actionItems().stream().anyMatch(i -> !"EXECUTED".equals(i.actionStatus()))) {
                            throw new ProcessRejectedException(
                                    "P006 every action item must have execution evidence before acceptance");
                        }
                        if (repository.acceptAllActionItems(actor.tenantId(), meetingId, actor.employeeId())
                                != current.actionItems().size()) {
                            throw new ProcessRejectedException("P006 action-item acceptance conflict");
                        }
                    }
                    current = aggregate(actor.tenantId(), meetingId);
                }
                case "S10" -> {
                    requireAction(action, "RESOLVE_OVERDUE");
                    repository.markOverdueFacts(actor.tenantId(), meetingId, Instant.now(), actor.employeeId());
                }
                case "S11" -> requireAction(action, "ARCHIVE");
                default -> throw new ProcessRejectedException(
                        "P006 action is not allowed from the current source node");
            }

            Meeting moved = advance(
                    actor, current.meeting(), node, action, scopedKey(idempotencyKey, "workflow"), command.reason());
            if ("S03".equals(node)) repository.markPublished(actor.tenantId(), meetingId, actor.employeeId());
            if ("S05".equals(node)) repository.markMeetingHeld(actor.tenantId(), meetingId, actor.employeeId());
            if ("S11".equals(node)) {
                Meeting closed = requiredMeeting(actor.tenantId(), meetingId);
                if (!"END".equals(closed.currentNodeCode())
                        || closed.archivedAt() == null
                        || closed.actualEndAt() == null) {
                    throw new ProcessRejectedException("P006 archive did not persist terminal facts");
                }
            }
            return aggregate(actor.tenantId(), moved.id());
        });
    }

    private MeetingAggregate attendanceReceipt(
            DatabaseSecurityContext actor, MeetingAggregate current, String action, String idempotencyKey) {
        MeetingItem participant = current.participants().stream()
                .filter(i -> actor.employeeId().equals(i.relatedObjectId()))
                .findFirst()
                .orElseThrow(() -> new ProcessRejectedException("P006 actor is not a meeting participant"));
        if (!"PENDING".equals(participant.actionStatus())) {
            throw new ProcessRejectedException("P006 attendance receipt already exists");
        }
        if (repository.markAttendance(
                        actor.tenantId(),
                        participant.id(),
                        participant.versionNo(),
                        "ATTEND".equals(action) ? "ATTENDED" : "LEAVE",
                        actor.employeeId())
                != 1) {
            throw new ProcessRejectedException("P006 concurrent attendance receipt conflict");
        }
        MeetingAggregate after = aggregate(actor.tenantId(), current.meeting().id());
        if (after.participants().stream()
                .allMatch(i -> Set.of("ATTENDED", "LEAVE").contains(i.actionStatus()))) {
            Meeting moved = advance(
                    actor,
                    after.meeting(),
                    "S04",
                    "COMPLETE_ATTENDANCE",
                    scopedKey(idempotencyKey, "attendance-stage"),
                    null);
            return aggregate(actor.tenantId(), moved.id());
        }
        return after;
    }

    private MeetingAggregate actionEvidence(
            DatabaseSecurityContext actor, MeetingAggregate current, ActionCommand command, String idempotencyKey) {
        Map<UUID, String> evidence = command.actionEvidence() == null ? Map.of() : command.actionEvidence();
        if (evidence.isEmpty()) throw new ProcessRejectedException("P006 action evidence is required");
        for (Map.Entry<UUID, String> entry : evidence.entrySet()) {
            MeetingItem item = current.actionItems().stream()
                    .filter(i -> entry.getKey().equals(i.id()))
                    .findFirst()
                    .orElseThrow(() -> new ProcessRejectedException("P006 action item not found"));
            if (!actor.employeeId().equals(item.actionOwnerEmployeeId())) {
                throw new ProcessRejectedException("P006 only the action owner may submit execution evidence");
            }
            requireText(entry.getValue(), "actionEvidence");
            if (!Set.of("OPEN", "REWORK").contains(item.actionStatus())) {
                throw new ProcessRejectedException("P006 action item is not executable from current status");
            }
            if (repository.submitActionEvidence(
                            actor.tenantId(),
                            item.id(),
                            item.versionNo(),
                            entry.getValue().trim(),
                            actor.employeeId())
                    != 1) {
                throw new ProcessRejectedException("P006 concurrent action evidence conflict");
            }
        }
        MeetingAggregate after = aggregate(actor.tenantId(), current.meeting().id());
        if (!after.actionItems().isEmpty()
                && after.actionItems().stream().allMatch(i -> "EXECUTED".equals(i.actionStatus()))) {
            Meeting moved = advance(
                    actor,
                    after.meeting(),
                    "S08",
                    "SUBMIT_ACTION_EVIDENCE",
                    scopedKey(idempotencyKey, "execution-stage"),
                    null);
            return aggregate(actor.tenantId(), moved.id());
        }
        return after;
    }

    public Optional<MeetingAggregate> find(DatabaseSecurityContext actor, UUID meetingId) {
        requireActor(actor);
        return transactions.required(actor, () -> repository
                .findMeeting(actor.tenantId(), meetingId)
                .map(m -> new MeetingAggregate(m, repository.listItems(actor.tenantId(), meetingId))));
    }

    public List<MeetingAggregate> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.listMeetings(actor.tenantId()).stream()
                .map(m -> new MeetingAggregate(m, repository.listItems(actor.tenantId(), m.id())))
                .toList());
    }

    private Meeting advance(
            DatabaseSecurityContext actor, Meeting current, String node, String action, String key, String reason) {
        WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
        if (!node.equals(runtime.instance().currentNodeCode()) || !node.equals(current.currentNodeCode())) {
            throw new ProcessRejectedException("P006 business projection is stale relative to workflow runtime");
        }
        if (runtime.task() == null) throw new ProcessRejectedException("P006 current workflow task is missing");
        taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                actor.tenantId(), runtime.task().id(), actor.employeeId()));
        WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                actor.tenantId(),
                actor.employeeId(),
                actor.identityId(),
                current.workflowInstanceId(),
                runtime.task().id(),
                node,
                action,
                reason,
                key));
        Instant archivedAt = "END".equals(result.instance().currentNodeCode()) ? Instant.now() : null;
        Instant closedAt = "END".equals(result.instance().currentNodeCode())
                ? result.instance().finishedAt()
                : null;
        if (repository.moveStatus(
                        actor.tenantId(),
                        current.id(),
                        current.versionNo(),
                        label(result.instance().currentNodeCode()),
                        archivedAt,
                        closedAt,
                        actor.employeeId())
                != 1) {
            throw new ProcessRejectedException("P006 concurrent aggregate transition conflict");
        }
        Meeting moved = requiredMeeting(actor.tenantId(), current.id());
        emit(
                actor,
                moved,
                action,
                result.instance().currentNodeCode(),
                participantIds(aggregate(actor.tenantId(), current.id())));
        return moved;
    }

    private void emit(
            DatabaseSecurityContext actor, Meeting meeting, String event, String node, List<UUID> recipients) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("meetingId", meeting.id().toString());
        payload.put("businessNo", meeting.businessNo());
        payload.put("event", event);
        payload.put("nodeCode", node);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        outbox.enqueue(new TransactionalOutboxService.Command(
                actor.tenantId(),
                actor.employeeId(),
                AGGREGATE_TYPE,
                meeting.id(),
                EVENT_TYPE,
                1,
                json(payload),
                "p006:" + meeting.id() + ":" + meeting.versionNo()));
    }

    private List<WorkflowFormService.FieldValue> initialFormValues(
            WorkflowRuntimeService.Instance instance, Meeting m) {
        List<WorkflowFormService.FieldValue> values = new ArrayList<>();
        values.add(text("process_code", PROCESS_CODE));
        values.add(text("official_subject", m.officialSubject()));
        values.add(text("official_content", m.officialContent()));
        values.add(text("start_at", m.startAt().toString()));
        addText(values, "venue_channel", m.venueChannel());
        values.add(text("visibility_level", m.visibilityLevel()));
        return List.copyOf(values);
    }

    private static WorkflowFormService.FieldValue text(String code, String value) {
        return new WorkflowFormService.FieldValue(code, "TEXT", value, null, null, null, null, null, "P1", false);
    }

    private static void addText(List<WorkflowFormService.FieldValue> values, String code, String value) {
        if (trimToNull(value) != null) values.add(text(code, value.trim()));
    }

    private void validateActionItems(MeetingAggregate current, List<ActionItemInput> items) {
        if (items == null || items.isEmpty()) throw new ProcessRejectedException("P006 action items are required");
        Set<UUID> participants = current.participants().stream()
                .map(MeetingItem::relatedObjectId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (ActionItemInput item : items) {
            requireText(item.title(), "actionItem.title");
            if (item.ownerEmployeeId() == null || !participants.contains(item.ownerEmployeeId())) {
                throw new ProcessRejectedException("P006 action owner must be a meeting participant");
            }
            if (item.dueAt() == null) throw new ProcessRejectedException("P006 action dueAt is required");
        }
    }

    private static List<UUID> requiredItemIds(List<UUID> ids) {
        List<UUID> result = ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (result.isEmpty()) throw new ProcessRejectedException("P006 action item ids are required for rework");
        return result;
    }

    private static List<UUID> participantIds(MeetingAggregate aggregate) {
        return aggregate.participants().stream()
                .map(MeetingItem::relatedObjectId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private MeetingAggregate aggregate(UUID tenantId, UUID id) {
        Meeting meeting = requiredMeeting(tenantId, id);
        return new MeetingAggregate(meeting, repository.listItems(tenantId, id));
    }

    private Meeting requiredMeeting(UUID tenantId, UUID id) {
        return repository
                .findMeeting(tenantId, id)
                .orElseThrow(() -> new ProcessRejectedException("P006 meeting not found"));
    }

    private static void requireAction(String actual, String expected) {
        if (!expected.equals(actual))
            throw new ProcessRejectedException("P006 action is not allowed from the current source node");
    }

    private static void validateCreate(CreateCommand c) {
        Objects.requireNonNull(c, "P006 create command is required");
        requireText(c.officialSubject(), "officialSubject");
        requireText(c.officialType(), "officialType");
        requireText(c.officialContent(), "officialContent");
        requireText(c.attendanceType(), "attendanceType");
        requireText(c.visibilityLevel(), "visibilityLevel");
        if (!VISIBILITY_LEVELS.contains(c.visibilityLevel().trim())) {
            throw new ProcessRejectedException("P006 visibilityLevel is not source-backed");
        }
        if (c.ownerCenterId() == null) throw new ProcessRejectedException("P006 ownerCenterId is required");
        if (c.startAt() == null) throw new ProcessRejectedException("P006 startAt is required");
        if (c.participantEmployeeIds() == null || c.participantEmployeeIds().isEmpty()) {
            throw new ProcessRejectedException("P006 participants are required");
        }
    }

    private static void requireActor(DatabaseSecurityContext a) {
        if (a == null
                || a.tenantId() == null
                || a.userId() == null
                || a.identityId() == null
                || a.employeeId() == null
                || a.orgId() == null
                || a.positionId() == null) {
            throw new ProcessRejectedException("P006 authenticated employee context is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new ProcessRejectedException("P006 required field is missing: " + field);
    }

    private static String safeAction(String actionCode) {
        if (actionCode == null) return "";
        String value = actionCode.trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9_]{1,32}") ? value : "";
    }

    private static String scopedKey(String key, String suffix) {
        if (key == null || key.isBlank()) throw new ProcessRejectedException("P006 idempotency key is required");
        String value = key + ":" + suffix;
        if (value.length() > 128) throw new ProcessRejectedException("P006 idempotency key is too long");
        return value;
    }

    private String json(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new ProcessRejectedException("P006 JSON serialization failed", ex);
        }
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode result = mapper.createArrayNode();
        ids.stream().filter(Objects::nonNull).distinct().forEach(id -> result.add(id.toString()));
        return result;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String label(String node) {
        return switch (node) {
            case "S01" -> "议题征集";
            case "S02" -> "材料完整性检查";
            case "S03" -> "会议发布";
            case "S04" -> "签到与请假";
            case "S05" -> "会议召开";
            case "S06" -> "主持人确认纪要";
            case "S07" -> "行动项生成";
            case "S08" -> "责任人执行";
            case "S09" -> "验收与返工";
            case "S10" -> "逾期升级";
            case "S11" -> "归档复盘";
            case "END" -> "已关闭";
            default -> throw new ProcessRejectedException("P006 workflow returned unknown source node: " + node);
        };
    }

    public interface Repository {
        Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);

        Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode);

        List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId);

        boolean areActiveEmployeesInOrg(UUID tenantId, UUID orgId, List<UUID> employeeIds);

        void insertMeeting(Meeting meeting, UUID actorId);

        void replaceAgenda(UUID tenantId, UUID meetingId, List<String> agendaItems, UUID actorId);

        void insertParticipants(UUID tenantId, UUID meetingId, List<UUID> participantIds, UUID actorId);

        int bindWorkflowAndMove(
                UUID tenantId,
                UUID meetingId,
                int expectedVersion,
                UUID workflowInstanceId,
                String status,
                UUID actorId);

        int moveStatus(
                UUID tenantId,
                UUID meetingId,
                int expectedVersion,
                String status,
                Instant archivedAt,
                Instant closedAt,
                UUID actorId);

        int markPublished(UUID tenantId, UUID meetingId, UUID actorId);

        int markMeetingHeld(UUID tenantId, UUID meetingId, UUID actorId);

        int confirmMinutes(UUID tenantId, UUID meetingId, int expectedVersion, String minutesText, UUID actorId);

        int markAttendance(UUID tenantId, UUID participantItemId, int expectedVersion, String status, UUID actorId);

        void replaceActionItems(UUID tenantId, UUID meetingId, List<ActionItemInput> items, UUID actorId);

        int submitActionEvidence(UUID tenantId, UUID itemId, int expectedVersion, String evidence, UUID actorId);

        int returnActionItems(UUID tenantId, UUID meetingId, List<UUID> itemIds, UUID actorId);

        int acceptAllActionItems(UUID tenantId, UUID meetingId, UUID actorId);

        int markOverdueFacts(UUID tenantId, UUID meetingId, Instant now, UUID actorId);

        Optional<Meeting> findMeeting(UUID tenantId, UUID meetingId);

        List<Meeting> listMeetings(UUID tenantId);

        List<MeetingItem> listItems(UUID tenantId, UUID meetingId);
    }

    public record FormRef(UUID id, int versionNo) {}

    public record CreateCommand(
            String officialSubject,
            String officialType,
            String officialContent,
            String attendanceType,
            String visibilityLevel,
            String venueChannel,
            UUID ownerCenterId,
            LocalDate businessDate,
            Instant startAt,
            List<UUID> participantEmployeeIds,
            List<String> agendaItems) {}

    public record ActionItemInput(String title, UUID ownerEmployeeId, Instant dueAt) {}

    public record ActionCommand(
            int expectedVersion,
            String minutesText,
            List<ActionItemInput> actionItems,
            Map<UUID, String> actionEvidence,
            List<UUID> actionItemIds,
            String reason) {}

    public record Meeting(
            UUID id,
            UUID tenantId,
            String businessNo,
            UUID workflowInstanceId,
            String workflowInstanceNo,
            String currentNodeCode,
            String status,
            int versionNo,
            String officialSubject,
            String officialType,
            String officialContent,
            String attendanceType,
            String employeeEventType,
            String issuerHostId,
            String visibilityLevel,
            String venueChannel,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            LocalDate businessDate,
            Instant startAt,
            Instant publishedAt,
            String minutesText,
            Instant minutesConfirmedAt,
            Instant archivedAt,
            Instant actualEndAt,
            Instant updatedAt) {}

    public record MeetingItem(
            UUID id,
            UUID meetingId,
            String fieldCode,
            int itemSeq,
            String itemName,
            String itemValueText,
            UUID relatedObjectId,
            UUID actionOwnerEmployeeId,
            Instant actionDueAt,
            String actionStatus,
            String executionEvidence,
            Instant completedAt,
            Instant acceptedAt,
            UUID acceptedBy,
            int reworkCount,
            Instant escalatedAt,
            int versionNo) {}

    public record MeetingAggregate(Meeting meeting, List<MeetingItem> items) {
        public MeetingAggregate {
            items = List.copyOf(items == null ? List.of() : items);
        }

        public List<MeetingItem> participants() {
            return items.stream()
                    .filter(i -> "PARTICIPANT".equals(i.fieldCode()))
                    .toList();
        }

        public List<MeetingItem> agendaItems() {
            return items.stream().filter(i -> "AGENDA".equals(i.fieldCode())).toList();
        }

        public List<MeetingItem> actionItems() {
            return items.stream()
                    .filter(i -> "ACTION_ITEM".equals(i.fieldCode()))
                    .toList();
        }

        public MeetingAggregate metadataOnly() {
            Meeting m = new Meeting(
                    meeting.id(),
                    meeting.tenantId(),
                    meeting.businessNo(),
                    meeting.workflowInstanceId(),
                    meeting.workflowInstanceNo(),
                    meeting.currentNodeCode(),
                    meeting.status(),
                    meeting.versionNo(),
                    null,
                    meeting.officialType(),
                    null,
                    meeting.attendanceType(),
                    meeting.employeeEventType(),
                    null,
                    meeting.visibilityLevel(),
                    null,
                    meeting.ownerCenterId(),
                    null,
                    meeting.businessDate(),
                    meeting.startAt(),
                    meeting.publishedAt(),
                    null,
                    meeting.minutesConfirmedAt(),
                    meeting.archivedAt(),
                    meeting.actualEndAt(),
                    meeting.updatedAt());
            return new MeetingAggregate(m, List.of());
        }

        public boolean employeeVisible(UUID employeeId) {
            return participants().stream().anyMatch(i -> employeeId.equals(i.relatedObjectId()))
                    || actionItems().stream().anyMatch(i -> employeeId.equals(i.actionOwnerEmployeeId()));
        }
    }
}
