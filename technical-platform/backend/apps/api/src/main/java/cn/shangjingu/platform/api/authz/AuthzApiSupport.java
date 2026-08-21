package cn.shangjingu.platform.api.authz;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.authz.domain.AuthzRecords.Mutation;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class AuthzApiSupport {
    public static final String MODULE_READ = "authz.module.read";
    public static final String MODULE_MANAGE = "authz.module.manage";
    public static final String ORG_MODULE_MANAGE = "authz.org.module.manage";
    public static final String POSITION_ROLE_MANAGE = "authz.position.role.manage";
    public static final String CONFIG_PREVIEW = "authz.config.preview";
    public static final String CONFIG_MANAGE = "authz.config.manage";
    public static final String STEP_UP_PURPOSE = "AUTHZ_CONFIG_MANAGE";
    private static final int REQUIRED_MFA_LEVEL = 2;

    private final AuthorizationService authorization;
    private final StepUpService stepUp;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public AuthzApiSupport(
            AuthorizationService authorization,
            StepUpService stepUp,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.authorization = authorization;
        this.stepUp = stepUp;
        this.audit = audit;
        this.mapper = mapper;
    }

    public DatabaseSecurityContext context(SessionPrincipal principal) {
        var subject = principal.context();
        return new DatabaseSecurityContext(
                subject.tenantId(),
                subject.userId(),
                subject.identityId(),
                subject.employeeId(),
                subject.appointmentId(),
                subject.orgId(),
                subject.positionId());
    }

    public void requireRead(SessionPrincipal principal) {
        requireAny(principal, MODULE_READ, CONFIG_MANAGE);
    }

    public void requirePreview(SessionPrincipal principal) {
        requireAny(principal, CONFIG_PREVIEW, CONFIG_MANAGE);
    }

    public void requireWrite(
            SessionPrincipal principal, String capabilityPermission, String stepUpTicket) {
        require(authorization.authorizeAction(principal.context(), capabilityPermission));
        require(authorization.authorizeAction(principal.context(), CONFIG_MANAGE));
        stepUp.requireAndConsume(
                stepUpTicket, principal.context(), STEP_UP_PURPOSE, REQUIRED_MFA_LEVEL);
    }

    public void auditRead(
            SessionPrincipal principal, String action, String resourceType, UUID resourceId) {
        audit.recordOperation(principal.context(), action, resourceType, resourceId);
    }

    public void auditMutation(
            SessionPrincipal principal,
            String action,
            String resourceType,
            UUID resourceId,
            Mutation<?> mutation) {
        try {
            String before = mapper.writeValueAsString(mutation.before());
            String after = mapper.writeValueAsString(mutation.after());
            audit.recordConfigurationChange(
                    principal.context(), action, resourceType, resourceId, before, after);
        } catch (Exception ex) {
            throw new IllegalStateException("authorization configuration audit snapshot failed", ex);
        }
    }

    private void requireAny(SessionPrincipal principal, String... permissions) {
        for (String permission : permissions) {
            if (authorization.authorizeAction(principal.context(), permission).allowed()) return;
        }
        throw new AccessDeniedException("authz configuration permission denied");
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw new AccessDeniedException("authz configuration permission denied: " + decision.reason());
        }
    }
}
