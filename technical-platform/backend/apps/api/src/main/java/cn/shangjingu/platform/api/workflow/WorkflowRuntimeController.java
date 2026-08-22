package cn.shangjingu.platform.api.workflow;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.workflow.WorkflowException;
import cn.shangjingu.platform.workflow.WorkflowFormService;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import cn.shangjingu.platform.workflow.WorkflowTaskAssignmentService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowRuntimeController {
    public static final String PERMISSION_START = "workflow.runtime.start";
    public static final String PERMISSION_READ = "workflow.runtime.read";
    public static final String PERMISSION_ACT = "workflow.runtime.act";
    public static final String PERMISSION_CLAIM = "workflow.task.claim";
    public static final String PERMISSION_FORM_SUBMIT = "workflow.form.submit";

    private static final Set<String> START_FIELDS = Set.of(
            "versionId",
            "businessObjectType",
            "businessObjectId",
            "businessObjectNo",
            "title",
            "priority",
            "contextSnapshot");
    private static final Set<String> ACTION_FIELDS = Set.of("taskId", "expectedNodeCode", "actionCode", "reason");
    private static final Set<String> SUBMIT_FIELDS =
            Set.of("instanceId", "taskId", "formDefinitionId", "expectedFormVersion", "values");
    private static final Set<String> VALUE_FIELDS = Set.of(
            "fieldCode",
            "valueType",
            "valueText",
            "valueNumber",
            "valueDatetime",
            "valueBoolean",
            "valueJson",
            "searchHash",
            "sensitiveLevel",
            "encrypted");

    private final WorkflowRuntimeService runtime;
    private final WorkflowTaskAssignmentService assignments;
    private final WorkflowFormService forms;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;

    public WorkflowRuntimeController(
            WorkflowRuntimeService runtime,
            WorkflowTaskAssignmentService assignments,
            WorkflowFormService forms,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit) {
        this.runtime = runtime;
        this.assignments = assignments;
        this.forms = forms;
        this.authorization = authorization;
        this.audit = audit;
    }

    @PostMapping("/instances")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRuntimeService.Result start(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        SessionContext subject = subject(principal);
        rejectUnknown(body, START_FIELDS, "workflow start");
        authorizeAction(subject, PERMISSION_START);
        authorizeData(subject, PERMISSION_START, selfTarget(subject));
        audit.recordOperation(subject, "WORKFLOW_START_ATTEMPT", "workflow.wf_instance", null);
        return runtime.start(new WorkflowRuntimeService.StartCommand(
                subject.tenantId(),
                subject.employeeId(),
                subject.identityId(),
                requiredUuid(body, "versionId"),
                requiredText(body, "businessObjectType"),
                optionalUuid(body, "businessObjectId"),
                optionalText(body, "businessObjectNo"),
                requiredText(body, "title"),
                optionalText(body, "priority"),
                optionalNode(body, "contextSnapshot"),
                requiredText(idempotencyKey, "Idempotency-Key")));
    }

    @GetMapping("/instances/{instanceId}")
    public WorkflowRuntimeService.Result get(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID instanceId) {
        SessionContext subject = subject(principal);
        authorizeAction(subject, PERMISSION_READ);
        WorkflowRuntimeService.Result result = runtime.get(subject.tenantId(), instanceId);
        authorizeData(subject, PERMISSION_READ, runtimeTarget(result));
        return result;
    }

    @PostMapping("/instances/{instanceId}/actions")
    public WorkflowRuntimeService.Result act(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID instanceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        SessionContext subject = subject(principal);
        rejectUnknown(body, ACTION_FIELDS, "workflow action");
        authorizeAction(subject, PERMISSION_ACT);
        WorkflowRuntimeService.Result current = runtime.get(subject.tenantId(), instanceId);
        authorizeData(subject, PERMISSION_ACT, runtimeTarget(current));
        audit.recordOperation(subject, "WORKFLOW_ACTION_ATTEMPT", "workflow.wf_instance", instanceId);
        return runtime.act(new WorkflowRuntimeService.ActionCommand(
                subject.tenantId(),
                subject.employeeId(),
                subject.identityId(),
                instanceId,
                optionalUuid(body, "taskId"),
                requiredText(body, "expectedNodeCode"),
                requiredText(body, "actionCode"),
                optionalText(body, "reason"),
                requiredText(idempotencyKey, "Idempotency-Key")));
    }

    @PostMapping("/instances/{instanceId}/tasks/{taskId}/claim")
    public WorkflowTaskAssignmentService.ClaimResult claim(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID instanceId,
            @PathVariable UUID taskId) {
        SessionContext subject = subject(principal);
        authorizeAction(subject, PERMISSION_CLAIM);
        authorizeData(subject, PERMISSION_CLAIM, claimantTarget(subject));
        WorkflowRuntimeService.Result current = runtime.get(subject.tenantId(), instanceId);
        if (current.task() == null || !taskId.equals(current.task().id())) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION,
                    "workflow task is not the current task of the requested instance");
        }
        audit.recordOperation(subject, "WORKFLOW_TASK_CLAIM_ATTEMPT", "workflow.wf_task", taskId);
        return assignments.claim(
                new WorkflowTaskAssignmentService.ClaimCommand(subject.tenantId(), taskId, subject.employeeId()));
    }

    @PostMapping("/forms/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowFormService.Submission submitForm(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        SessionContext subject = subject(principal);
        rejectUnknown(body, SUBMIT_FIELDS, "workflow form submission");
        UUID instanceId = requiredUuid(body, "instanceId");
        UUID taskId = optionalUuid(body, "taskId");
        authorizeAction(subject, PERMISSION_FORM_SUBMIT);
        WorkflowRuntimeService.Result current = runtime.get(subject.tenantId(), instanceId);
        authorizeData(subject, PERMISSION_FORM_SUBMIT, runtimeTarget(current));
        if (current.task() == null && taskId != null) {
            throw new WorkflowException(
                    WorkflowException.Code.STALE_VERSION,
                    "workflow form task is not current for the requested instance");
        }
        if (current.task() != null) {
            if (taskId == null || !taskId.equals(current.task().id())) {
                throw new WorkflowException(
                        WorkflowException.Code.STALE_VERSION, "workflow form submission must bind the current task");
            }
            if (current.task().assigneeId() == null) {
                throw new WorkflowException(
                        WorkflowException.Code.NO_ELIGIBLE_APPROVER,
                        "workflow task must be claimed before submitting its form");
            }
            if (!subject.employeeId().equals(current.task().assigneeId())) {
                throw new WorkflowException(
                        WorkflowException.Code.FORBIDDEN, "workflow form task belongs to a different assignee");
            }
        }
        audit.recordOperation(subject, "WORKFLOW_FORM_SUBMIT_ATTEMPT", "workflow.wf_instance", instanceId);
        return forms.submit(new WorkflowFormService.SubmitForm(
                subject.tenantId(),
                subject.employeeId(),
                subject.identityId(),
                instanceId,
                taskId,
                requiredUuid(body, "formDefinitionId"),
                requiredInt(body, "expectedFormVersion"),
                values(body.get("values")),
                requiredText(idempotencyKey, "Idempotency-Key")));
    }

    private List<WorkflowFormService.FieldValue> values(JsonNode node) {
        if (node == null || !node.isArray()) throw WorkflowException.invalid("values must be an array");
        List<WorkflowFormService.FieldValue> values = new ArrayList<>();
        for (JsonNode value : node) {
            rejectUnknown(value, VALUE_FIELDS, "workflow form field value");
            values.add(new WorkflowFormService.FieldValue(
                    requiredText(value, "fieldCode"),
                    requiredText(value, "valueType"),
                    optionalText(value, "valueText"),
                    optionalDecimal(value, "valueNumber"),
                    optionalInstant(value, "valueDatetime"),
                    optionalBoolean(value, "valueBoolean"),
                    optionalNode(value, "valueJson"),
                    optionalText(value, "searchHash"),
                    optionalText(value, "sensitiveLevel"),
                    requiredBoolean(value, "encrypted")));
        }
        return List.copyOf(values);
    }

    private void authorizeAction(SessionContext subject, String permissionCode) {
        AuthorizationDecision decision = authorization.authorizeAction(subject, permissionCode);
        if (!decision.allowed()) throw denied(decision);
    }

    private void authorizeData(SessionContext subject, String permissionCode, AuthorizationTarget target) {
        AuthorizationDecision decision = authorization.authorizeData(subject, permissionCode, target);
        if (!decision.allowed()) throw denied(decision);
    }

    private static AccessDeniedException denied(AuthorizationDecision decision) {
        return new AccessDeniedException("authorization denied: " + decision.reason());
    }

    private static AuthorizationTarget selfTarget(SessionContext subject) {
        return new AuthorizationTarget(
                subject.tenantId(), subject.employeeId(), subject.orgId(), subject.positionId(), subject.employeeId());
    }

    private static AuthorizationTarget claimantTarget(SessionContext subject) {
        return new AuthorizationTarget(
                subject.tenantId(), subject.employeeId(), subject.orgId(), subject.positionId(), null);
    }

    private static AuthorizationTarget runtimeTarget(WorkflowRuntimeService.Result result) {
        UUID initiatorId = result.instance().initiatorId();
        UUID scopedEmployeeId = result.task() != null && result.task().assigneeId() != null
                ? result.task().assigneeId()
                : initiatorId;
        return new AuthorizationTarget(result.instance().tenantId(), scopedEmployeeId, null, null, initiatorId);
    }

    private static SessionContext subject(SessionPrincipal principal) {
        if (principal == null || principal.context() == null) {
            throw new AccessDeniedException("authenticated session required");
        }
        SessionContext context = principal.context();
        if (context.tenantId() == null || context.identityId() == null || context.employeeId() == null) {
            throw new AccessDeniedException("active tenant, identity and employee are required");
        }
        return context;
    }

    private static void rejectUnknown(JsonNode body, Set<String> allowed, String label) {
        if (body == null || !body.isObject()) throw WorkflowException.invalid(label + " body must be a JSON object");
        Set<String> unknown = new HashSet<>();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw WorkflowException.invalid(label + " contains unsupported fields: " + unknown);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw WorkflowException.invalid(field + " is required");
        }
        return value.textValue().trim();
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) throw WorkflowException.invalid(field + " is required");
        return value.trim();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw WorkflowException.invalid(field + " must be text");
        return value.textValue();
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        UUID value = optionalUuid(node, field);
        if (value == null) throw WorkflowException.invalid(field + " is required");
        return value;
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw WorkflowException.invalid(field + " must be UUID");
        }
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw WorkflowException.invalid(field + " must be integer");
        }
        return value.intValue();
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw WorkflowException.invalid(field + " must be numeric");
        return value.decimalValue();
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw WorkflowException.invalid(field + " must be ISO-8601 instant");
        }
    }

    private static Boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw WorkflowException.invalid(field + " must be boolean");
        return value.booleanValue();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        Boolean value = optionalBoolean(node, field);
        if (value == null) throw WorkflowException.invalid(field + " is required");
        return value;
    }

    private static JsonNode optionalNode(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.deepCopy();
    }
}
