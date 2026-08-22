package cn.shangjingu.platform.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeControllerTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID IDENTITY = UUID.fromString("00000000-0000-0000-0000-000000000703");
    private static final UUID EMPLOYEE = UUID.fromString("00000000-0000-0000-0000-000000000704");
    private static final UUID APPOINTMENT = UUID.fromString("00000000-0000-0000-0000-000000000705");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000706");
    private static final UUID POSITION = UUID.fromString("00000000-0000-0000-0000-000000000707");
    private static final UUID INITIATOR = UUID.fromString("00000000-0000-0000-0000-000000000708");
    private static final UUID VERSION = UUID.fromString("00000000-0000-0000-0000-000000000709");
    private static final UUID DEFINITION = UUID.fromString("00000000-0000-0000-0000-000000000710");
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000711");
    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000712");
    private static final UUID OTHER_TASK = UUID.fromString("00000000-0000-0000-0000-000000000713");
    private static final UUID FORM = UUID.fromString("00000000-0000-0000-0000-000000000714");

    @Mock
    WorkflowRuntimeService runtime;

    @Mock
    WorkflowTaskAssignmentService assignments;

    @Mock
    WorkflowFormService forms;

    @Mock
    AuthorizationService authorization;

    @Mock
    JdbcSecurityAuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkflowRuntimeController controller;
    private SessionPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new WorkflowRuntimeController(runtime, assignments, forms, authorization, audit);
        principal = new SessionPrincipal(
                "opaque-test-token",
                new SessionContext(
                        TENANT,
                        USER,
                        IDENTITY,
                        EMPLOYEE,
                        APPOINTMENT,
                        ORG,
                        POSITION,
                        Instant.parse("2026-08-08T08:00:00Z")));
    }

    @Test
    void startDerivesTenantActorAndIdentityOnlyFromAuthenticatedPrincipal() throws Exception {
        allowAction(WorkflowRuntimeController.PERMISSION_START);
        allowData(WorkflowRuntimeController.PERMISSION_START);
        WorkflowRuntimeService.Result expected = new WorkflowRuntimeService.Result(null, null, null, false);
        when(runtime.start(any())).thenReturn(expected);

        WorkflowRuntimeService.Result actual = controller.start(
                principal,
                "idem-start-701",
                mapper.readTree(
                        """
                        {
                          "versionId":"00000000-0000-0000-0000-000000000709",
                          "businessObjectType":"TEST_OBJECT",
                          "businessObjectId":"00000000-0000-0000-0000-000000000799",
                          "businessObjectNo":"OBJ-701",
                          "title":"C7 contract test",
                          "priority":"HIGH",
                          "contextSnapshot":{"riskLevel":"LOW"}
                        }
                        """));

        assertSame(expected, actual);
        ArgumentCaptor<WorkflowRuntimeService.StartCommand> command =
                ArgumentCaptor.forClass(WorkflowRuntimeService.StartCommand.class);
        verify(runtime).start(command.capture());
        assertEquals(TENANT, command.getValue().tenantId());
        assertEquals(EMPLOYEE, command.getValue().actorId());
        assertEquals(IDENTITY, command.getValue().operatorIdentityId());
        assertEquals(VERSION, command.getValue().versionId());
        assertEquals("TEST_OBJECT", command.getValue().businessObjectType());
        verify(audit).recordOperation(principal.context(), "WORKFLOW_START_ATTEMPT", "workflow.wf_instance", null);
    }

    @Test
    void forgedAuthorityAndTargetStateFieldsAreRejectedBeforeAnyServiceMutation() throws Exception {
        WorkflowException forged = assertThrows(
                WorkflowException.class,
                () -> controller.start(
                        principal,
                        "idem-forged-701",
                        mapper.readTree(
                                """
                        {
                          "versionId":"00000000-0000-0000-0000-000000000709",
                          "businessObjectType":"TEST_OBJECT",
                          "title":"forged",
                          "tenantId":"00000000-0000-0000-0000-000000009999",
                          "actorId":"00000000-0000-0000-0000-000000009998"
                        }
                        """)));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, forged.code());

        WorkflowException target = assertThrows(
                WorkflowException.class,
                () -> controller.act(
                        principal,
                        INSTANCE,
                        "idem-target-701",
                        mapper.readTree(
                                """
                        {
                          "taskId":"00000000-0000-0000-0000-000000000712",
                          "expectedNodeCode":"APPROVAL",
                          "actionCode":"APPROVE",
                          "targetNodeCode":"END",
                          "targetState":"COMPLETED"
                        }
                        """)));
        assertEquals(WorkflowException.Code.INVALID_ARGUMENT, target.code());
        verifyNoInteractions(runtime, assignments, forms, audit);
    }

    @Test
    void actionUsesServerPrincipalAndResourceAssigneeForDataScope() throws Exception {
        allowAction(WorkflowRuntimeController.PERMISSION_ACT);
        allowData(WorkflowRuntimeController.PERMISSION_ACT);
        WorkflowRuntimeService.Result current = currentResult(TASK, EMPLOYEE);
        when(runtime.get(TENANT, INSTANCE)).thenReturn(current);
        when(runtime.act(any())).thenReturn(current);

        WorkflowRuntimeService.Result result = controller.act(
                principal,
                INSTANCE,
                "idem-action-701",
                mapper.readTree(
                        """
                        {
                          "taskId":"00000000-0000-0000-0000-000000000712",
                          "expectedNodeCode":"APPROVAL",
                          "actionCode":"APPROVE",
                          "reason":"approved"
                        }
                        """));
        assertSame(current, result);

        ArgumentCaptor<WorkflowRuntimeService.ActionCommand> command =
                ArgumentCaptor.forClass(WorkflowRuntimeService.ActionCommand.class);
        verify(runtime).act(command.capture());
        assertEquals(TENANT, command.getValue().tenantId());
        assertEquals(EMPLOYEE, command.getValue().actorId());
        assertEquals(IDENTITY, command.getValue().operatorIdentityId());
        assertEquals(INSTANCE, command.getValue().instanceId());
        assertEquals(TASK, command.getValue().taskId());
        assertEquals("APPROVAL", command.getValue().expectedNodeCode());
        assertEquals("APPROVE", command.getValue().actionCode());

        ArgumentCaptor<AuthorizationTarget> target = ArgumentCaptor.forClass(AuthorizationTarget.class);
        verify(authorization)
                .authorizeData(eq(principal.context()), eq(WorkflowRuntimeController.PERMISSION_ACT), target.capture());
        assertEquals(EMPLOYEE, target.getValue().employeeId());
        assertEquals(INITIATOR, target.getValue().ownerEmployeeId());
        assertNull(target.getValue().orgId());
        assertNull(target.getValue().positionId());
        verify(audit).recordOperation(principal.context(), "WORKFLOW_ACTION_ATTEMPT", "workflow.wf_instance", INSTANCE);
    }

    @Test
    void claimUsesClaimantScopeWithoutForgingResourceOwnership() {
        allowAction(WorkflowRuntimeController.PERMISSION_CLAIM);
        allowData(WorkflowRuntimeController.PERMISSION_CLAIM);
        when(runtime.get(TENANT, INSTANCE)).thenReturn(currentResult(TASK, null));
        WorkflowTaskAssignmentService.ClaimResult expected =
                new WorkflowTaskAssignmentService.ClaimResult(TASK, INSTANCE, EMPLOYEE, List.of(EMPLOYEE), false);
        when(assignments.claim(any())).thenReturn(expected);

        WorkflowTaskAssignmentService.ClaimResult actual = controller.claim(principal, INSTANCE, TASK);
        assertSame(expected, actual);

        ArgumentCaptor<AuthorizationTarget> target = ArgumentCaptor.forClass(AuthorizationTarget.class);
        verify(authorization)
                .authorizeData(
                        eq(principal.context()), eq(WorkflowRuntimeController.PERMISSION_CLAIM), target.capture());
        assertEquals(EMPLOYEE, target.getValue().employeeId());
        assertEquals(ORG, target.getValue().orgId());
        assertEquals(POSITION, target.getValue().positionId());
        assertNull(target.getValue().ownerEmployeeId());
        verify(audit).recordOperation(principal.context(), "WORKFLOW_TASK_CLAIM_ATTEMPT", "workflow.wf_task", TASK);
    }

    @Test
    void claimRejectsWrongInstanceTaskBeforeAssignmentMutation() {
        allowAction(WorkflowRuntimeController.PERMISSION_CLAIM);
        allowData(WorkflowRuntimeController.PERMISSION_CLAIM);
        when(runtime.get(TENANT, INSTANCE)).thenReturn(currentResult(OTHER_TASK, null));

        WorkflowException error =
                assertThrows(WorkflowException.class, () -> controller.claim(principal, INSTANCE, TASK));

        assertEquals(WorkflowException.Code.STALE_VERSION, error.code());
        verifyNoInteractions(assignments, audit);
    }

    @Test
    void formSubmissionRequiresCurrentClaimedTaskAndUsesPrincipalAsSubmitter() throws Exception {
        allowAction(WorkflowRuntimeController.PERMISSION_FORM_SUBMIT);
        allowData(WorkflowRuntimeController.PERMISSION_FORM_SUBMIT);
        WorkflowRuntimeService.Result current = currentResult(TASK, EMPLOYEE);
        when(runtime.get(TENANT, INSTANCE)).thenReturn(current);
        when(forms.submit(any())).thenReturn(null);

        controller.submitForm(
                principal,
                "idem-form-701",
                mapper.readTree(
                        """
                        {
                          "instanceId":"00000000-0000-0000-0000-000000000711",
                          "taskId":"00000000-0000-0000-0000-000000000712",
                          "formDefinitionId":"00000000-0000-0000-0000-000000000714",
                          "expectedFormVersion":3,
                          "values":[]
                        }
                        """));

        ArgumentCaptor<WorkflowFormService.SubmitForm> command =
                ArgumentCaptor.forClass(WorkflowFormService.SubmitForm.class);
        verify(forms).submit(command.capture());
        assertEquals(TENANT, command.getValue().tenantId());
        assertEquals(EMPLOYEE, command.getValue().submitterId());
        assertEquals(IDENTITY, command.getValue().operatorIdentityId());
        assertEquals(INSTANCE, command.getValue().instanceId());
        assertEquals(TASK, command.getValue().taskId());
        assertEquals(FORM, command.getValue().formDefinitionId());
        assertEquals(3, command.getValue().expectedFormVersion());
        verify(audit)
                .recordOperation(principal.context(), "WORKFLOW_FORM_SUBMIT_ATTEMPT", "workflow.wf_instance", INSTANCE);
    }

    @Test
    void formSubmissionOnUnclaimedTaskFailsClosedBeforePersistence() throws Exception {
        allowAction(WorkflowRuntimeController.PERMISSION_FORM_SUBMIT);
        allowData(WorkflowRuntimeController.PERMISSION_FORM_SUBMIT);
        when(runtime.get(TENANT, INSTANCE)).thenReturn(currentResult(TASK, null));

        WorkflowException error = assertThrows(
                WorkflowException.class,
                () -> controller.submitForm(
                        principal,
                        "idem-form-unclaimed-701",
                        mapper.readTree(
                                """
                        {
                          "instanceId":"00000000-0000-0000-0000-000000000711",
                          "taskId":"00000000-0000-0000-0000-000000000712",
                          "formDefinitionId":"00000000-0000-0000-0000-000000000714",
                          "expectedFormVersion":3,
                          "values":[]
                        }
                        """)));

        assertEquals(WorkflowException.Code.NO_ELIGIBLE_APPROVER, error.code());
        verifyNoInteractions(forms, audit);
    }

    private void allowAction(String permissionCode) {
        when(authorization.authorizeAction(principal.context(), permissionCode))
                .thenReturn(AuthorizationDecision.allow(permissionCode, "SELF"));
    }

    private void allowData(String permissionCode) {
        when(authorization.authorizeData(eq(principal.context()), eq(permissionCode), any(AuthorizationTarget.class)))
                .thenReturn(AuthorizationDecision.allow(permissionCode, "SELF"));
    }

    private WorkflowRuntimeService.Result currentResult(UUID taskId, UUID assigneeId) {
        WorkflowRuntimeService.Instance instance = new WorkflowRuntimeService.Instance(
                INSTANCE,
                TENANT,
                "WFI-C7",
                DEFINITION,
                VERSION,
                "P001",
                "TEST_OBJECT",
                null,
                "OBJ-701",
                "C7 runtime",
                INITIATOR,
                "APPROVAL",
                WorkflowRuntimeService.RUNNING,
                "NORMAL",
                Instant.parse("2026-08-08T08:00:00Z"),
                null,
                null,
                mapper.createObjectNode());
        WorkflowRuntimeService.Task task = taskId == null
                ? null
                : new WorkflowRuntimeService.Task(
                        taskId,
                        TENANT,
                        INSTANCE,
                        "WFT-C7",
                        "APPROVAL",
                        "APPROVAL",
                        assigneeId,
                        mapper.createObjectNode(),
                        WorkflowRuntimeService.PENDING,
                        Instant.parse("2026-08-08T08:01:00Z"),
                        null,
                        null,
                        null,
                        null);
        return new WorkflowRuntimeService.Result(instance, task, null, false);
    }
}
