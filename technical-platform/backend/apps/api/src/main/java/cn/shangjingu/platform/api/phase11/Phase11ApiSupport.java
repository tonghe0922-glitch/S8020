package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class Phase11ApiSupport {
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public Phase11ApiSupport(
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    public void requireAction(
            SessionPrincipal principal, String permission, String processCode) {
        require(
                authorization.authorizeAction(principal.context(), permission),
                processCode);
    }

    public void requireData(
            SessionPrincipal principal,
            String permission,
            AuthorizationTarget target,
            String processCode) {
        require(
                authorization.authorizeData(principal.context(), permission, target),
                processCode);
    }

    public boolean allowed(SessionPrincipal principal, String permission) {
        return authorization.authorizeAction(principal.context(), permission).allowed();
    }

    public boolean allowedData(
            SessionPrincipal principal, String permission, AuthorizationTarget target) {
        return authorization.authorizeData(principal.context(), permission, target).allowed();
    }

    public AuthorizationTarget target(
            SessionPrincipal principal, Phase11Record record) {
        return new AuthorizationTarget(
                record.tenantId(),
                record.ownerEmployeeId(),
                record.ownerCenterId(),
                principal.context().positionId(),
                record.ownerEmployeeId());
    }

    public AuthorizationTarget target(
            SessionPrincipal principal, UUID ownerCenterId, UUID ownerEmployeeId) {
        return new AuthorizationTarget(
                principal.context().tenantId(),
                ownerEmployeeId,
                ownerCenterId,
                principal.context().positionId(),
                ownerEmployeeId);
    }

    public DatabaseSecurityContext context(SessionPrincipal principal) {
        var session = principal.context();
        return new DatabaseSecurityContext(
                session.tenantId(),
                session.userId(),
                session.identityId(),
                session.employeeId(),
                session.appointmentId(),
                session.orgId(),
                session.positionId());
    }

    public String hash(Object value, String processCode) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    processCode + " request cannot be hashed", exception);
        }
    }

    public String safeAction(String value) {
        if (value == null) {
            return "INVALID";
        }
        String action = value.trim().toUpperCase(Locale.ROOT);
        return action.matches("[A-Z0-9_]{1,48}") ? action : "INVALID";
    }

    public void audit(
            SessionPrincipal principal,
            String action,
            String resourceType,
            UUID resourceId) {
        audit.recordOperation(principal.context(), action, resourceType, resourceId);
    }

    private static void require(
            AuthorizationDecision decision, String processCode) {
        if (!decision.allowed()) {
            throw new AccessDeniedException(
                    processCode + " authorization denied: " + decision.reason());
        }
    }
}
