package cn.shangjingu.platform.api.workflow;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.OpaqueAccessTokenFilter;
import cn.shangjingu.platform.api.security.SecurityConfiguration;
import cn.shangjingu.platform.api.security.SecurityProblemHandler;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.domain.AuthorizationSnapshot;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.workflow.WorkflowFormService;
import cn.shangjingu.platform.workflow.WorkflowRuntimeService;
import cn.shangjingu.platform.workflow.WorkflowTaskAssignmentService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = WorkflowRuntimeController.class)
@Import({SecurityConfiguration.class, OpaqueAccessTokenFilter.class, SecurityProblemHandler.class, WorkflowApiExceptionHandler.class})
class WorkflowApiSecurityTest {
    private static final String TOKEN = "opaque-c7-security-token";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000721");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000722");
    private static final UUID IDENTITY = UUID.fromString("00000000-0000-0000-0000-000000000723");
    private static final UUID EMPLOYEE = UUID.fromString("00000000-0000-0000-0000-000000000724");
    private static final UUID APPOINTMENT = UUID.fromString("00000000-0000-0000-0000-000000000725");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000726");
    private static final UUID POSITION = UUID.fromString("00000000-0000-0000-0000-000000000727");
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000728");
    private static final SessionContext SUBJECT = new SessionContext(
            TENANT, USER, IDENTITY, EMPLOYEE, APPOINTMENT, ORG, POSITION,
            Instant.parse("2026-08-08T08:00:00Z"));

    @Autowired MockMvc mockMvc;

    @MockitoBean WorkflowRuntimeService runtime;
    @MockitoBean WorkflowTaskAssignmentService assignments;
    @MockitoBean WorkflowFormService forms;
    @MockitoBean AuthorizationService authorization;
    @MockitoBean JdbcSecurityAuditService audit;
    @MockitoBean SessionService sessions;
    @MockitoBean IdentityDirectoryService identities;

    @Test
    void unauthenticatedWorkflowRouteIs401() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/instances/{instanceId}", INSTANCE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        verifyNoInteractions(runtime, authorization);
    }

    @Test
    void authenticatedSessionWithoutWorkflowPermissionIs403() throws Exception {
        authenticate();
        when(authorization.authorizeAction(SUBJECT, WorkflowRuntimeController.PERMISSION_READ))
                .thenReturn(AuthorizationDecision.deny(
                        AuthorizationDecision.Reason.NO_PERMISSION,
                        WorkflowRuntimeController.PERMISSION_READ,
                        null));

        mockMvc.perform(get("/api/v1/workflow/instances/{instanceId}", INSTANCE)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));

        verifyNoInteractions(runtime);
        verify(audit).recordSecurityEvent(
                eq(TENANT), eq(USER), eq(IDENTITY), eq("AUTHORIZATION_DENIED"), eq("WARN"), eq("DENIED"));
    }

    @Test
    void forgedAuthorityFieldsAreRejectedAs400OverHttp() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/workflow/instances")
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "idem-c7-forged-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "versionId":"00000000-0000-0000-0000-000000000729",
                                  "businessObjectType":"TEST_OBJECT",
                                  "title":"forged",
                                  "tenantId":"00000000-0000-0000-0000-000000009999",
                                  "actorId":"00000000-0000-0000-0000-000000009998"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(runtime, authorization, audit);
    }

    @Test
    void targetStateAndTargetNodeAreRejectedAs400OverHttp() throws Exception {
        authenticate();

        mockMvc.perform(post("/api/v1/workflow/instances/{instanceId}/actions", INSTANCE)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "idem-c7-target-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedNodeCode":"APPROVAL",
                                  "actionCode":"APPROVE",
                                  "targetNodeCode":"END",
                                  "targetState":"COMPLETED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(runtime, authorization, audit);
    }

    private void authenticate() {
        when(sessions.authenticateAccess(TOKEN)).thenReturn(Optional.of(SUBJECT));
        when(identities.authorization(SUBJECT)).thenReturn(new AuthorizationSnapshot(Set.of(), List.of()));
    }
}
