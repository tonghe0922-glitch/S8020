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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P005 policy/notice version, server-resolved audience and per-recipient receipt lifecycle. */
@Service
public final class NoticeReceiptService {
    public static final String PROCESS_CODE = "P005";
    public static final String INITIAL_FORM_CODE = "CTR-P005-F03";
    public static final String EVENT_TYPE = "P005_NOTICE_EVENT";
    public static final String AGGREGATE_TYPE = "P005_NOTICE";
    public static final String MANAGE_PERMISSION = "p005.notice.manage";
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

    public NoticeReceiptService(
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

    public NoticeAggregate publish(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, PublishCommand command) {
        requireActor(actor);
        validate(command);
        if (!actor.orgId().equals(command.targetCenterId())) {
            throw new ProcessRejectedException("P005 publish target must be the authenticated center");
        }
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "collaboration.notice", proposedId, IDEMPOTENCY_TTL);
            if (claim.existing()) return aggregate(actor.tenantId(), claim.resourceId());

            UUID workflowVersion = repository.latestPublishedWorkflowVersion(actor.tenantId(), PROCESS_CODE)
                    .orElseThrow(() -> new ProcessRejectedException("P005 published workflow version is not configured"));
            FormRef form = repository.latestPublishedForm(actor.tenantId(), INITIAL_FORM_CODE, PROCESS_CODE, "S01")
                    .orElseThrow(() -> new ProcessRejectedException("P005 initial published form CTR-P005-F03 is not configured"));
            List<AudienceMember> recipients = repository.resolveRecipients(
                    actor.tenantId(), command.targetCenterId(), trimToNull(command.targetPositionCode()));
            if (recipients.isEmpty()) throw new ProcessRejectedException("P005 server-resolved audience is empty");
            List<UUID> managers = repository.permissionCandidates(actor.tenantId(), MANAGE_PERMISSION, actor.orgId());
            if (!managers.contains(actor.employeeId())) {
                throw new ProcessRejectedException("P005 publisher must remain an eligible center manager candidate");
            }

            String policyCode = normalizePolicyCode(command.policyCode());
            int policyVersion = repository.nextPolicyVersion(actor.tenantId(), policyCode);
            int passScore = command.understandingPassScore() == null ? 80 : command.understandingPassScore();
            String businessNo = numbers.next(actor.tenantId(), actor.employeeId(), PROCESS_CODE);
            Instant now = Instant.now();
            Notice notice = new Notice(
                    claim.resourceId(), actor.tenantId(), businessNo, null, null, "S01", label("S01"), 0,
                    policyCode, policyVersion, command.officialSubject().trim(), command.officialType().trim(),
                    command.officialContent().trim(), command.periodOrCourseNo().trim(), command.visibilityLevel().trim(),
                    trimToNull(command.venueChannel()), actor.orgId(), actor.employeeId(), command.targetCenterId(),
                    trimToNull(command.targetPositionCode()), passScore, now,
                    command.businessDate() == null ? LocalDate.now() : command.businessDate(),
                    command.effectiveStartAt(), command.effectiveEndAt(), command.executionDueAt(), null, null, now);
            repository.insertNotice(notice, recipientScope(command.targetCenterId(), command.targetPositionCode()), actor.employeeId());
            repository.insertRecipients(actor.tenantId(), notice.id(), recipients);

            ObjectNode context = mapper.createObjectNode();
            context.put("ownerEmployeeId", actor.employeeId().toString());
            context.put("ownerCenterId", actor.orgId().toString());
            context.put("targetCenterId", command.targetCenterId().toString());
            context.put("policyCode", policyCode);
            context.put("policyVersion", policyVersion);
            context.set("managerCandidateIds", uuidArray(managers));
            context.set("recipientEmployeeIds", uuidArray(recipients.stream().map(AudienceMember::employeeId).toList()));

            WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), workflowVersion,
                    "collaboration.notice", notice.id(), notice.businessNo(), notice.officialSubject(), "NORMAL",
                    context, scopedKey(idempotencyKey, "start")));
            forms.submit(new WorkflowFormService.SubmitForm(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    form.id(), form.versionNo(), initialFormValues(started.instance(), notice), scopedKey(idempotencyKey, "form")));

            WorkflowRuntimeService.Result published = workflow.act(new WorkflowRuntimeService.ActionCommand(
                    actor.tenantId(), actor.employeeId(), actor.identityId(), started.instance().id(), null,
                    "S01", "PUBLISH", null, scopedKey(idempotencyKey, "s01")));
            if (repository.bindWorkflowAndMove(
                    actor.tenantId(), notice.id(), 0, published.instance().id(), label("S02"), actor.employeeId()) != 1) {
                throw new ProcessRejectedException("P005 concurrent publish transition conflict");
            }
            emit(actor.tenantId(), actor.employeeId(), notice, "S01", "PUBLISH", "S02", managers);

            Notice s02 = requiredNotice(actor.tenantId(), notice.id());
            Notice s03 = advance(actor, s02, "S02", "RESOLVE_AUDIENCE", scopedKey(idempotencyKey, "s02"), null, managers);
            List<UUID> recipientIds = recipients.stream().map(AudienceMember::employeeId).toList();
            Notice s04 = advance(actor, s03, "S03", "QUEUE_DELIVERY", scopedKey(idempotencyKey, "s03"), null, recipientIds);
            return aggregate(actor.tenantId(), s04.id());
        });
    }

    public NoticeAggregate markRead(
            DatabaseSecurityContext actor, UUID noticeId, String idempotencyKey, String requestHash, ReceiptCommand command) {
        return receipt(actor, noticeId, idempotencyKey, requestHash, "S04", "READ", command);
    }

    public NoticeAggregate confirm(
            DatabaseSecurityContext actor, UUID noticeId, String idempotencyKey, String requestHash, ReceiptCommand command) {
        return receipt(actor, noticeId, idempotencyKey, requestHash, "S05", "CONFIRM", command);
    }

    public NoticeAggregate understanding(
            DatabaseSecurityContext actor, UUID noticeId, String idempotencyKey, String requestHash, UnderstandingCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P005 understanding command is required");
        if (command.score() < 0 || command.score() > 100) {
            throw new ProcessRejectedException("P005 understanding score must be between 0 and 100");
        }
        return transactions.required(actor, () -> {
            NoticeAggregate current = aggregate(actor.tenantId(), noticeId);
            Recipient recipient = ownRecipient(current, actor.employeeId());
            if (claimReceipt(actor, recipient, idempotencyKey, requestHash, "understanding")) return current;
            requireNode(current.notice(), "S06");
            if (recipient.versionNo() != command.expectedRecipientVersion()) {
                throw new ProcessRejectedException("P005 recipient version conflict");
            }
            if (recipient.confirmedAt() == null) {
                throw new ProcessRejectedException("P005 confirmation is required before understanding validation");
            }
            if (recipient.understandingPassedAt() != null) {
                throw new ProcessRejectedException("P005 understanding validation is already passed");
            }
            boolean passed = command.score() >= current.notice().understandingPassScore();
            if (repository.markUnderstanding(
                    actor.tenantId(), recipient.id(), recipient.versionNo(), command.score(), passed, actor.employeeId()) != 1) {
                throw new ProcessRejectedException("P005 concurrent understanding update conflict");
            }
            repository.appendReceiptEvent(actor.tenantId(), noticeId, recipient.id(), recipient.employeeId(), actor.employeeId(),
                    passed ? "UNDERSTANDING_PASSED" : "UNDERSTANDING_FAILED", understandingEvidence(command.score(), passed));
            NoticeAggregate after = aggregate(actor.tenantId(), noticeId);
            if (passed && all(after.recipients(), Stage.UNDERSTANDING)) {
                Notice moved = advance(actor, after.notice(), "S06", "PASS_UNDERSTANDING",
                        scopedKey(idempotencyKey, "stage"), null, employeeIds(after.recipients()));
                return aggregate(actor.tenantId(), moved.id());
            }
            return after;
        });
    }

    public NoticeAggregate execute(
            DatabaseSecurityContext actor, UUID noticeId, String idempotencyKey, String requestHash, ExecutionCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P005 execution command is required");
        if (command.summary() == null || command.summary().isBlank()) {
            throw new ProcessRejectedException("P005 execution summary is required");
        }
        return transactions.required(actor, () -> {
            NoticeAggregate current = aggregate(actor.tenantId(), noticeId);
            Recipient recipient = ownRecipient(current, actor.employeeId());
            if (claimReceipt(actor, recipient, idempotencyKey, requestHash, "execution")) return current;
            requireNode(current.notice(), "S07");
            if (recipient.versionNo() != command.expectedRecipientVersion()) {
                throw new ProcessRejectedException("P005 recipient version conflict");
            }
            if (recipient.understandingPassedAt() == null) {
                throw new ProcessRejectedException("P005 understanding validation must pass before execution");
            }
            if (recipient.executedAt() != null) {
                throw new ProcessRejectedException("P005 execution receipt is already submitted");
            }
            if (repository.markExecuted(
                    actor.tenantId(), recipient.id(), recipient.versionNo(), command.summary().trim(), actor.employeeId()) != 1) {
                throw new ProcessRejectedException("P005 concurrent execution update conflict");
            }
            repository.appendReceiptEvent(actor.tenantId(), noticeId, recipient.id(), recipient.employeeId(), actor.employeeId(),
                    "EXECUTED", "{\"summarySubmitted\":true}");
            NoticeAggregate after = aggregate(actor.tenantId(), noticeId);
            if (all(after.recipients(), Stage.EXECUTION)) {
                Notice moved = advance(actor, after.notice(), "S07", "SUBMIT_EXECUTION",
                        scopedKey(idempotencyKey, "stage"), null, employeeIds(after.recipients()));
                return aggregate(actor.tenantId(), moved.id());
            }
            return after;
        });
    }

    public NoticeAggregate manage(
            DatabaseSecurityContext actor, UUID noticeId, String actionCode,
            String idempotencyKey, String requestHash, ManageCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P005 manage command is required");
        String action = safeAction(actionCode);
        if (action.isBlank()) throw new ProcessRejectedException("P005 action code is invalid");
        return transactions.required(actor, () -> {
            NoticeAggregate current = aggregate(actor.tenantId(), noticeId);
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                    "collaboration.notice.manage." + action.toLowerCase(Locale.ROOT), noticeId, IDEMPOTENCY_TTL);
            if (claim.existing()) return current;
            if (current.notice().versionNo() != command.expectedVersion()) {
                throw new ProcessRejectedException("P005 notice version conflict");
            }
            String node = current.notice().currentNodeCode();
            String expectedAction = switch (node) {
                case "S08" -> "ACCEPT_EXECUTION";
                case "S09" -> "RESOLVE_ESCALATIONS";
                case "S10" -> "ARCHIVE";
                default -> throw new ProcessRejectedException("P005 center action is not allowed from the current source node");
            };
            if (!expectedAction.equals(action)) {
                throw new ProcessRejectedException("P005 action is not allowed from the current source node");
            }
            if ("S08".equals(node) && !all(current.recipients(), Stage.EXECUTION)) {
                throw new ProcessRejectedException("P005 all recipient execution receipts are required before acceptance");
            }
            if ("S08".equals(node)) {
                for (Recipient recipient : current.recipients()) {
                    if (recipient.acceptedAt() == null) {
                        if (repository.markAccepted(actor.tenantId(), recipient.id(), recipient.versionNo(), actor.employeeId()) != 1) {
                            throw new ProcessRejectedException("P005 concurrent acceptance update conflict");
                        }
                        repository.appendReceiptEvent(actor.tenantId(), noticeId, recipient.id(), recipient.employeeId(),
                                actor.employeeId(), "ACCEPTED", null);
                    }
                }
            }
            Notice moved = advance(actor, current.notice(), node, action, scopedKey(idempotencyKey, "workflow"),
                    trimToNull(command.reason()), employeeIds(current.recipients()));
            if ("S10".equals(node)) {
                Notice closed = requiredNotice(actor.tenantId(), moved.id());
                if (!"END".equals(closed.currentNodeCode()) || closed.archivedAt() == null) {
                    throw new ProcessRejectedException("P005 archive transition did not persist terminal facts");
                }
            }
            return aggregate(actor.tenantId(), moved.id());
        });
    }

    public Optional<NoticeAggregate> find(DatabaseSecurityContext actor, UUID noticeId) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.findNotice(actor.tenantId(), noticeId)
                .map(notice -> new NoticeAggregate(notice, repository.listRecipients(actor.tenantId(), notice.id()))));
    }

    public List<NoticeAggregate> list(DatabaseSecurityContext actor) {
        requireActor(actor);
        return transactions.required(actor, () -> repository.listNotices(actor.tenantId()).stream()
                .map(notice -> new NoticeAggregate(notice, repository.listRecipients(actor.tenantId(), notice.id())))
                .toList());
    }

    private NoticeAggregate receipt(
            DatabaseSecurityContext actor, UUID noticeId, String idempotencyKey, String requestHash,
            String requiredNode, String operation, ReceiptCommand command) {
        requireActor(actor);
        Objects.requireNonNull(command, "P005 receipt command is required");
        return transactions.required(actor, () -> {
            NoticeAggregate current = aggregate(actor.tenantId(), noticeId);
            Recipient recipient = ownRecipient(current, actor.employeeId());
            if (claimReceipt(actor, recipient, idempotencyKey, requestHash, operation.toLowerCase(Locale.ROOT))) return current;
            requireNode(current.notice(), requiredNode);
            if (recipient.versionNo() != command.expectedRecipientVersion()) {
                throw new ProcessRejectedException("P005 recipient version conflict");
            }
            if ("READ".equals(operation)) {
                if (recipient.deliveredAt() == null) throw new ProcessRejectedException("P005 notice must be delivered before it can be read");
                if (recipient.readAt() != null) throw new ProcessRejectedException("P005 notice is already read");
                if (repository.markRead(actor.tenantId(), recipient.id(), recipient.versionNo(), actor.employeeId()) != 1) {
                    throw new ProcessRejectedException("P005 concurrent read update conflict");
                }
                repository.appendReceiptEvent(actor.tenantId(), noticeId, recipient.id(), recipient.employeeId(), actor.employeeId(), "READ", null);
            } else {
                if (recipient.readAt() == null) throw new ProcessRejectedException("P005 read receipt is required before confirmation");
                if (recipient.confirmedAt() != null) throw new ProcessRejectedException("P005 notice is already confirmed");
                if (repository.markConfirmed(actor.tenantId(), recipient.id(), recipient.versionNo(), actor.employeeId()) != 1) {
                    throw new ProcessRejectedException("P005 concurrent confirmation update conflict");
                }
                repository.appendReceiptEvent(actor.tenantId(), noticeId, recipient.id(), recipient.employeeId(), actor.employeeId(), "CONFIRMED", null);
            }
            NoticeAggregate after = aggregate(actor.tenantId(), noticeId);
            Stage stage = "READ".equals(operation) ? Stage.READ : Stage.CONFIRM;
            if (all(after.recipients(), stage)) {
                String action = stage == Stage.READ ? "COMPLETE_READ" : "COMPLETE_CONFIRM";
                Notice moved = advance(actor, after.notice(), requiredNode, action, scopedKey(idempotencyKey, "stage"),
                        null, employeeIds(after.recipients()));
                return aggregate(actor.tenantId(), moved.id());
            }
            return after;
        });
    }

    private boolean claimReceipt(
            DatabaseSecurityContext actor, Recipient recipient, String idempotencyKey, String requestHash, String suffix) {
        return idempotency.claim(
                actor.tenantId(), actor.employeeId(), idempotencyKey, requestHash,
                "collaboration.notice_recipient." + suffix, recipient.id(), IDEMPOTENCY_TTL).existing();
    }

    private Notice advance(
            DatabaseSecurityContext actor, Notice current, String node, String action, String workflowKey,
            String reason, List<UUID> eventRecipients) {
        WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
        if (!node.equals(runtime.instance().currentNodeCode()) || !node.equals(current.currentNodeCode())) {
            throw new ProcessRejectedException("P005 business projection is stale relative to workflow runtime");
        }
        if (runtime.task() == null) throw new ProcessRejectedException("P005 current workflow task is missing");
        taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                actor.tenantId(), runtime.task().id(), actor.employeeId()));
        WorkflowRuntimeService.Result result = workflow.act(new WorkflowRuntimeService.ActionCommand(
                actor.tenantId(), actor.employeeId(), actor.identityId(), current.workflowInstanceId(), runtime.task().id(),
                node, action, reason, workflowKey));
        Instant archivedAt = "S10".equals(node) && "END".equals(result.instance().currentNodeCode()) ? Instant.now() : null;
        Instant closedAt = "END".equals(result.instance().currentNodeCode()) ? result.instance().finishedAt() : null;
        if (repository.moveStatus(
                actor.tenantId(), current.id(), current.versionNo(), label(result.instance().currentNodeCode()),
                archivedAt, closedAt, actor.employeeId()) != 1) {
            throw new ProcessRejectedException("P005 concurrent aggregate transition conflict");
        }
        emit(actor.tenantId(), actor.employeeId(), current, node, action, result.instance().currentNodeCode(), eventRecipients);
        return requiredNotice(actor.tenantId(), current.id());
    }

    private NoticeAggregate aggregate(UUID tenantId, UUID noticeId) {
        Notice notice = requiredNotice(tenantId, noticeId);
        return new NoticeAggregate(notice, repository.listRecipients(tenantId, noticeId));
    }

    private Notice requiredNotice(UUID tenantId, UUID noticeId) {
        return repository.findNotice(tenantId, noticeId)
                .orElseThrow(() -> new ProcessRejectedException("P005 notice not found"));
    }

    private static Recipient ownRecipient(NoticeAggregate aggregate, UUID employeeId) {
        return aggregate.recipients().stream().filter(r -> employeeId.equals(r.employeeId())).findFirst()
                .orElseThrow(() -> new ProcessRejectedException("P005 employee is not in the server-resolved audience"));
    }

    private void emit(
            UUID tenantId, UUID actorId, Notice notice, String completedNode, String actionCode,
            String targetNode, List<UUID> recipients) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("noticeId", notice.id().toString());
        payload.put("businessNo", notice.businessNo());
        payload.put("policyCode", notice.policyCode());
        payload.put("policyVersion", notice.policyVersion());
        payload.put("event", stageEvent(completedNode));
        payload.put("actionCode", actionCode);
        payload.put("nodeCode", targetNode);
        payload.set("recipientEmployeeIds", uuidArray(recipients));
        outbox.enqueue(new TransactionalOutboxService.Command(
                tenantId, actorId, AGGREGATE_TYPE, notice.id(), EVENT_TYPE, 1, json(payload),
                "p005:" + notice.id() + ":" + completedNode.toLowerCase(Locale.ROOT)));
    }

    private List<WorkflowFormService.FieldValue> initialFormValues(
            WorkflowRuntimeService.Instance instance, Notice notice) {
        List<WorkflowFormService.FieldValue> values = new ArrayList<>();
        values.add(text("process_instance_no", instance.instanceNo()));
        values.add(text("process_code", PROCESS_CODE));
        values.add(text("form_code", INITIAL_FORM_CODE));
        values.add(text("policy_code", notice.policyCode()));
        values.add(text("official_subject", notice.officialSubject()));
        values.add(text("official_type", notice.officialType()));
        values.add(text("official_content", notice.officialContent()));
        values.add(text("period_or_course_no", notice.periodOrCourseNo()));
        values.add(text("visibility_level", notice.visibilityLevel()));
        values.add(text("target_center_id", notice.targetCenterId().toString()));
        addText(values, "target_position_code", notice.targetPositionCode());
        values.add(number("understanding_pass_score", notice.understandingPassScore()));
        if (notice.executionDueAt() != null) values.add(text("execution_due_at", notice.executionDueAt().toString()));
        return List.copyOf(values);
    }

    private static WorkflowFormService.FieldValue text(String code, String value) {
        return new WorkflowFormService.FieldValue(code, "TEXT", value, null, null, null, null, null, "P1", false);
    }

    private static WorkflowFormService.FieldValue number(String code, int value) {
        return new WorkflowFormService.FieldValue(code, "NUMBER", null, java.math.BigDecimal.valueOf(value), null, null, null, null, "P1", false);
    }

    private static void addText(List<WorkflowFormService.FieldValue> values, String code, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) values.add(text(code, normalized));
    }

    private String recipientScope(UUID centerId, String positionCode) {
        ObjectNode value = mapper.createObjectNode();
        value.put("resolver", "ACTIVE_ORG_POSITION");
        value.put("centerId", centerId.toString());
        if (trimToNull(positionCode) != null) value.put("positionCode", positionCode.trim());
        return json(value);
    }

    private String understandingEvidence(int score, boolean passed) {
        ObjectNode value = mapper.createObjectNode();
        value.put("score", score);
        value.put("passed", passed);
        return json(value);
    }

    private static boolean all(List<Recipient> recipients, Stage stage) {
        if (recipients.isEmpty()) return false;
        return recipients.stream().allMatch(recipient -> switch (stage) {
            case READ -> recipient.readAt() != null;
            case CONFIRM -> recipient.confirmedAt() != null;
            case UNDERSTANDING -> recipient.understandingPassedAt() != null;
            case EXECUTION -> recipient.executedAt() != null;
        });
    }

    private static List<UUID> employeeIds(List<Recipient> recipients) {
        return recipients.stream().map(Recipient::employeeId).distinct().toList();
    }

    private static String stageEvent(String node) {
        return switch (node) {
            case "S01" -> "P005.stage.01.completed";
            case "S02" -> "P005.stage.02.completed";
            case "S03" -> "P005.stage.03.completed";
            case "S04" -> "P005.stage.04.completed";
            case "S05" -> "P005.stage.05.completed";
            case "S06" -> "P005.stage.06.completed";
            case "S07" -> "P005.stage.07.completed";
            case "S08" -> "P005.stage.08.completed";
            case "S09" -> "P005.stage.09.completed";
            case "S10" -> "P005.stage.10.completed";
            default -> throw new ProcessRejectedException("P005 event requested for an unknown source node: " + node);
        };
    }

    public static String label(String node) {
        return switch (node) {
            case "S01" -> "制度/通知版本发布";
            case "S02" -> "按组织岗位确定范围";
            case "S03" -> "消息送达";
            case "S04" -> "员工阅读";
            case "S05" -> "确认/阅签";
            case "S06" -> "考试或理解验证";
            case "S07" -> "执行任务";
            case "S08" -> "责任人验收";
            case "S09" -> "未完成催办升级";
            case "S10" -> "档案移交";
            case "END" -> "已关闭";
            default -> throw new ProcessRejectedException("P005 workflow returned an unknown source node: " + node);
        };
    }

    private static void validate(PublishCommand command) {
        Objects.requireNonNull(command, "P005 publish command is required");
        requireText(command.policyCode(), "policyCode");
        requireText(command.officialSubject(), "officialSubject");
        requireText(command.officialType(), "officialType");
        requireText(command.officialContent(), "officialContent");
        requireText(command.periodOrCourseNo(), "periodOrCourseNo");
        requireText(command.visibilityLevel(), "visibilityLevel");
        if (!VISIBILITY_LEVELS.contains(command.visibilityLevel().trim())) {
            throw new ProcessRejectedException("P005 visibilityLevel is not source-backed");
        }
        if (command.targetCenterId() == null) throw new ProcessRejectedException("P005 targetCenterId is required");
        if (command.understandingPassScore() != null
                && (command.understandingPassScore() < 0 || command.understandingPassScore() > 100)) {
            throw new ProcessRejectedException("P005 understandingPassScore must be between 0 and 100");
        }
        if (command.effectiveEndAt() != null && command.effectiveStartAt() != null
                && command.effectiveEndAt().isBefore(command.effectiveStartAt())) {
            throw new ProcessRejectedException("P005 effectiveEndAt cannot be before effectiveStartAt");
        }
    }

    private static void requireActor(DatabaseSecurityContext actor) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null || actor.identityId() == null
                || actor.employeeId() == null || actor.orgId() == null || actor.positionId() == null) {
            throw new ProcessRejectedException("P005 authenticated employee context is required");
        }
    }

    private static void requireNode(Notice notice, String expected) {
        if (!expected.equals(notice.currentNodeCode())) {
            throw new ProcessRejectedException("P005 operation is not allowed from the current source node");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ProcessRejectedException("P005 required field is missing: " + field);
    }

    private static String normalizePolicyCode(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._-]{2,64}")) {
            throw new ProcessRejectedException("P005 policyCode must match [A-Z0-9._-]{2,64}");
        }
        return normalized;
    }

    private static String safeAction(String actionCode) {
        if (actionCode == null) return "";
        String value = actionCode.trim().toUpperCase(Locale.ROOT);
        return value.matches("[A-Z0-9_]{1,32}") ? value : "";
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException("P005 JSON serialization failed", ex); }
    }

    private ArrayNode uuidArray(List<UUID> ids) {
        ArrayNode array = mapper.createArrayNode();
        ids.stream().filter(Objects::nonNull).distinct().forEach(id -> array.add(id.toString()));
        return array;
    }

    private static String scopedKey(String key, String suffix) {
        if (key == null || key.isBlank()) throw new ProcessRejectedException("P005 idempotency key is required");
        String value = key + ":" + suffix;
        if (value.length() > 128) throw new ProcessRejectedException("P005 idempotency key is too long");
        return value;
    }

    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public interface Repository {
        Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode);
        Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode);
        List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId);
        List<AudienceMember> resolveRecipients(UUID tenantId, UUID centerId, String positionCode);
        int nextPolicyVersion(UUID tenantId, String policyCode);
        void insertNotice(Notice notice, String recipientScopeJson, UUID actorId);
        void insertRecipients(UUID tenantId, UUID noticeId, List<AudienceMember> recipients);
        int bindWorkflowAndMove(UUID tenantId, UUID noticeId, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId);
        int moveStatus(UUID tenantId, UUID noticeId, int expectedVersion, String status, Instant archivedAt, Instant closedAt, UUID actorId);
        int markRead(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId);
        int markConfirmed(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId);
        int markUnderstanding(UUID tenantId, UUID recipientId, int expectedVersion, int score, boolean passed, UUID actorId);
        int markExecuted(UUID tenantId, UUID recipientId, int expectedVersion, String summary, UUID actorId);
        int markAccepted(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId);
        void appendReceiptEvent(UUID tenantId, UUID noticeId, UUID recipientId, UUID employeeId, UUID actorId, String eventType, String evidenceJson);
        Optional<Notice> findNotice(UUID tenantId, UUID noticeId);
        List<Notice> listNotices(UUID tenantId);
        List<Recipient> listRecipients(UUID tenantId, UUID noticeId);
    }

    public record FormRef(UUID id, int versionNo) {}
    public record AudienceMember(UUID employeeId, UUID identityId, UUID orgId, UUID positionId, String positionCode) {}
    public record PublishCommand(
            String policyCode, String officialSubject, String officialType, String officialContent,
            String periodOrCourseNo, String visibilityLevel, String venueChannel, UUID targetCenterId,
            String targetPositionCode, Integer understandingPassScore, LocalDate businessDate,
            Instant effectiveStartAt, Instant effectiveEndAt, Instant executionDueAt) {}
    public record ReceiptCommand(int expectedRecipientVersion) {}
    public record UnderstandingCommand(int expectedRecipientVersion, int score) {}
    public record ExecutionCommand(int expectedRecipientVersion, String summary) {}
    public record ManageCommand(int expectedVersion, String reason) {}
    public record Notice(
            UUID id, UUID tenantId, String businessNo, UUID workflowInstanceId, String workflowInstanceNo,
            String currentNodeCode, String status, int versionNo, String policyCode, int policyVersion,
            String officialSubject, String officialType, String officialContent, String periodOrCourseNo,
            String visibilityLevel, String venueChannel, UUID ownerCenterId, UUID ownerEmployeeId,
            UUID targetCenterId, String targetPositionCode, int understandingPassScore, Instant publishedAt,
            LocalDate businessDate, Instant effectiveStartAt, Instant effectiveEndAt, Instant executionDueAt,
            Instant archivedAt, Instant actualEndAt, Instant updatedAt) {}
    public record Recipient(
            UUID id, UUID noticeId, UUID employeeId, UUID identityId, UUID orgId, UUID positionId, String positionCode,
            String deliveryStatus, Instant deliveredAt, Instant readAt, Instant confirmedAt, Integer understandingScore,
            Instant understandingPassedAt, String executionSummary, Instant executedAt, Instant acceptedAt,
            UUID acceptedBy, Instant lastRemindedAt, int escalationCount, int versionNo, Instant updatedAt) {}
    public record NoticeAggregate(Notice notice, List<Recipient> recipients) {
        public NoticeAggregate {
            recipients = List.copyOf(recipients == null ? List.of() : recipients);
        }
        public NoticeAggregate recipientView(UUID employeeId) {
            return new NoticeAggregate(notice, recipients.stream().filter(r -> employeeId.equals(r.employeeId())).toList());
        }
        public NoticeAggregate metadataOnly() {
            Notice n = new Notice(notice.id(), notice.tenantId(), notice.businessNo(), notice.workflowInstanceId(),
                    notice.workflowInstanceNo(), notice.currentNodeCode(), notice.status(), notice.versionNo(),
                    notice.policyCode(), notice.policyVersion(), null, notice.officialType(), null, null,
                    notice.visibilityLevel(), null, notice.ownerCenterId(), null, notice.targetCenterId(),
                    notice.targetPositionCode(), notice.understandingPassScore(), notice.publishedAt(), notice.businessDate(),
                    notice.effectiveStartAt(), notice.effectiveEndAt(), notice.executionDueAt(), notice.archivedAt(),
                    notice.actualEndAt(), notice.updatedAt());
            return new NoticeAggregate(n, List.of());
        }
        public int recipientCount() { return recipients.size(); }
        public long deliveredCount() { return recipients.stream().filter(r -> r.deliveredAt() != null).count(); }
        public long readCount() { return recipients.stream().filter(r -> r.readAt() != null).count(); }
        public long confirmedCount() { return recipients.stream().filter(r -> r.confirmedAt() != null).count(); }
        public long understandingPassedCount() { return recipients.stream().filter(r -> r.understandingPassedAt() != null).count(); }
        public long executedCount() { return recipients.stream().filter(r -> r.executedAt() != null).count(); }
        public long acceptedCount() { return recipients.stream().filter(r -> r.acceptedAt() != null).count(); }
    }

    private enum Stage { READ, CONFIRM, UNDERSTANDING, EXECUTION }
}
