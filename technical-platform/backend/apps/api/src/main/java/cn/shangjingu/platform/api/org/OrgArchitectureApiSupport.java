package cn.shangjingu.platform.api.org;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.org.domain.OrgArchitectureRecords.Mutation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class OrgArchitectureApiSupport {
    public static final String VIEW = "org.architecture.view";
    public static final String EDIT = "org.architecture.edit";
    public static final String REVIEW = "org.architecture.review";
    public static final String PUBLISH = "org.architecture.publish";
    public static final String MANAGE = "org.architecture.manage";

    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public OrgArchitectureApiSupport(
            AuthorizationService authorization, JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.authorization = authorization;
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

    public void requireView(SessionPrincipal principal) {
        requireAny(principal, VIEW, EDIT, REVIEW, PUBLISH, MANAGE);
    }

    public void requireEdit(SessionPrincipal principal) {
        requireAny(principal, EDIT, MANAGE);
    }

    public void requireDraftAccess(SessionPrincipal principal) {
        requireAny(principal, EDIT, REVIEW, PUBLISH, MANAGE);
    }

    public void requireReview(SessionPrincipal principal) {
        requireAny(principal, REVIEW, MANAGE);
    }

    public void requirePublish(SessionPrincipal principal) {
        requireAny(principal, PUBLISH, MANAGE);
    }

    public void requireManage(SessionPrincipal principal) {
        require(authorization.authorizeAction(principal.context(), MANAGE));
    }

    public void auditRead(SessionPrincipal principal, String action, String resourceType, UUID resourceId) {
        audit.recordOperation(principal.context(), action, resourceType, resourceId);
    }

    public void auditMutation(
            SessionPrincipal principal, String action, String resourceType, UUID resourceId, Mutation<?> mutation) {
        try {
            audit.recordConfigurationChange(
                    principal.context(),
                    action,
                    resourceType,
                    resourceId,
                    mapper.writeValueAsString(mutation.before()),
                    mapper.writeValueAsString(mutation.after()));
        } catch (Exception exception) {
            throw new IllegalStateException("organization architecture audit snapshot failed", exception);
        }
    }

    public void auditDraft(SessionPrincipal principal, String action, UUID draftId, Object before, Object after) {
        try {
            audit.recordConfigurationChange(
                    principal.context(),
                    action,
                    "org.architecture_draft",
                    draftId,
                    mapper.writeValueAsString(before),
                    mapper.writeValueAsString(after));
        } catch (Exception exception) {
            throw new IllegalStateException("organization architecture draft audit snapshot failed", exception);
        }
    }

    private void requireAny(SessionPrincipal principal, String... permissions) {
        for (String permission : permissions) {
            if (authorization.authorizeAction(principal.context(), permission).allowed()) return;
        }
        throw new AccessDeniedException("organization architecture permission denied");
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw new AccessDeniedException("organization architecture permission denied: " + decision.reason());
        }
    }
}
