package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowFormService;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import cn.shangjingu.platform.workflow.WorkflowTaskAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Phase11WorkflowCoordinator {
    private final Phase11Repository repository;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService taskAssignment;
    private final WorkflowFormService forms;
    private final ObjectMapper mapper;

    public Phase11WorkflowCoordinator(
            Phase11Repository repository,
            WorkflowRuntimeService workflow,
            WorkflowTaskAssignmentService taskAssignment,
            WorkflowFormService forms,
            ObjectMapper mapper) {
        this.repository = repository;
        this.workflow = workflow;
        this.taskAssignment = taskAssignment;
        this.forms = forms;
        this.mapper = mapper;
    }

    public Started start(
            DatabaseSecurityContext actor,
            Phase11Process process,
            Phase11Record record,
            Phase11CreateData data,
            String idempotencyKey) {
        UUID workflowVersion = repository
                .latestPublishedWorkflowVersion(actor.tenantId(), process.code())
                .orElseThrow(() -> rejected(process, "published workflow version is not configured"));
        Phase11Repository.FormRef form = repository
                .latestPublishedForm(actor.tenantId(), process.initialFormCode(), process.code(), "S01")
                .orElseThrow(() -> rejected(process, "initial published form is not configured"));

        List<UUID> managers = candidates(actor, process, process.managerPermission(), data.ownerCenterId(), "manager");
        List<UUID> specialists =
                candidates(actor, process, process.specialistPermission(), data.ownerCenterId(), "specialist");
        List<UUID> appealReviewers = process == Phase11Process.P014
                ? candidates(actor, process, "p014.discipline.appeal", data.ownerCenterId(), "appeal reviewer")
                : specialists;
        List<UUID> remediators = process == Phase11Process.P014
                ? candidates(actor, process, "p014.discipline.remediate", data.ownerCenterId(), "remediator")
                : specialists;
        List<UUID> appealOrRemediation = new ArrayList<>(appealReviewers);
        appealOrRemediation.addAll(remediators);

        ObjectNode context = mapper.createObjectNode();
        context.put("ownerEmployeeId", data.ownerEmployeeId().toString());
        context.put("ownerCenterId", data.ownerCenterId().toString());
        context.set("employeeCandidateIds", uuidArray(List.of(data.ownerEmployeeId())));
        context.set("managerCandidateIds", uuidArray(managers));
        context.set("specialistCandidateIds", uuidArray(specialists));
        context.set("calibratorCandidateIds", uuidArray(specialists));
        context.set("appealReviewerIds", uuidArray(appealReviewers));
        context.set("investigatorCandidateIds", uuidArray(managers));
        context.set("decisionCandidateIds", uuidArray(specialists));
        context.set("remediationCandidateIds", uuidArray(remediators));
        context.set("appealOrRemediationCandidateIds", uuidArray(appealOrRemediation));
        context.set(
                "recusedEmployeeIds",
                process == Phase11Process.P014 ? uuidArray(List.of(data.ownerEmployeeId())) : mapper.createArrayNode());
        context.put("riskLevel", record.riskLevel());

        WorkflowRuntimeService.Result started = workflow.start(new WorkflowRuntimeService.StartCommand(
                actor.tenantId(),
                actor.employeeId(),
                actor.identityId(),
                workflowVersion,
                process.table(),
                record.id(),
                record.businessNo(),
                record.subject(),
                record.priority(),
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
                initialFormValues(process, record, data),
                scopedKey(idempotencyKey, "form")));
        WorkflowRuntimeService.Result submitted = workflow.act(new WorkflowRuntimeService.ActionCommand(
                actor.tenantId(),
                actor.employeeId(),
                actor.identityId(),
                started.instance().id(),
                null,
                "S01",
                process.initialAction(),
                null,
                scopedKey(idempotencyKey, "s01")));
        return new Started(submitted.instance().id(), submitted.instance().currentNodeCode());
    }

    public WorkflowRuntimeService.Result advance(
            DatabaseSecurityContext actor,
            Phase11Process process,
            Phase11Record current,
            String action,
            String reason,
            String idempotencyKey) {
        WorkflowRuntimeService.Result runtime = workflow.get(actor.tenantId(), current.workflowInstanceId());
        if (!current.currentNodeCode().equals(runtime.instance().currentNodeCode())) {
            throw rejected(process, "business state is stale relative to workflow runtime");
        }
        if (runtime.task() == null) {
            throw rejected(process, "current workflow task is missing");
        }
        taskAssignment.claim(new WorkflowTaskAssignmentService.ClaimCommand(
                actor.tenantId(), runtime.task().id(), actor.employeeId()));
        return workflow.act(new WorkflowRuntimeService.ActionCommand(
                actor.tenantId(),
                actor.employeeId(),
                actor.identityId(),
                current.workflowInstanceId(),
                runtime.task().id(),
                current.currentNodeCode(),
                action,
                reason,
                scopedKey(idempotencyKey, action.toLowerCase())));
    }

    private List<UUID> candidates(
            DatabaseSecurityContext actor, Phase11Process process, String permission, UUID orgId, String role) {
        List<UUID> candidates = repository.permissionCandidates(actor.tenantId(), permission, orgId);
        if (candidates.isEmpty()) {
            throw rejected(process, "no eligible " + role + " candidate is configured");
        }
        return candidates;
    }

    private List<WorkflowFormService.FieldValue> initialFormValues(
            Phase11Process process, Phase11Record record, Phase11CreateData data) {
        List<WorkflowFormService.FieldValue> values = new ArrayList<>();
        values.add(text("process_code", process.code(), "P1"));
        values.add(text("business_no", record.businessNo(), "P1"));
        values.add(text("subject", data.subject(), "P1"));
        values.add(text("reason", data.reason(), "P1"));
        values.add(text("owner_employee_id", data.ownerEmployeeId().toString(), "P1"));
        values.add(text("owner_center_id", data.ownerCenterId().toString(), "P1"));
        values.add(text("fact_summary", data.factSummary(), "P2"));
        if (data.periodNo() != null) {
            values.add(text("period_no", data.periodNo(), "P1"));
        }
        if (data.contentVersion() != null) {
            values.add(text("content_version", data.contentVersion(), "P1"));
        }
        return List.copyOf(values);
    }

    private static WorkflowFormService.FieldValue text(String code, String value, String sensitiveLevel) {
        return new WorkflowFormService.FieldValue(
                code, "TEXT", value, null, null, null, null, null, sensitiveLevel, false);
    }

    private ArrayNode uuidArray(List<UUID> values) {
        ArrayNode array = mapper.createArrayNode();
        values.stream().distinct().forEach(value -> array.add(value.toString()));
        return array;
    }

    private static String scopedKey(String key, String suffix) {
        if (key == null || key.isBlank()) {
            throw new ProcessRejectedException("PHASE-11 idempotency key is required");
        }
        String value = key.trim() + ":" + suffix;
        if (value.length() > 128) {
            throw new ProcessRejectedException("PHASE-11 idempotency key is too long");
        }
        return value;
    }

    private static ProcessRejectedException rejected(Phase11Process process, String message) {
        return new ProcessRejectedException(process.code() + " " + message);
    }

    public record Started(UUID workflowInstanceId, String currentNodeCode) {}
}
