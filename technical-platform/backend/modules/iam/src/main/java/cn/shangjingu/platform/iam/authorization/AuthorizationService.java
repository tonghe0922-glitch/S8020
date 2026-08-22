package cn.shangjingu.platform.iam.authorization;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.domain.AuthorizationGrant;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private final IdentityDirectoryService identities;
    private final DataScopeEvaluator dataScopes;

    public AuthorizationService(IdentityDirectoryService identities, DataScopeEvaluator dataScopes) {
        this.identities = identities;
        this.dataScopes = dataScopes;
    }

    public AuthorizationDecision authorizeAction(SessionContext subject, String permissionCode) {
        List<AuthorizationGrant> grants = matchingGrants(subject, permissionCode);
        if (grants.isEmpty()) {
            return AuthorizationDecision.deny(AuthorizationDecision.Reason.NO_PERMISSION, permissionCode, null);
        }
        return AuthorizationDecision.allow(permissionCode, grants.getFirst().dataScopeCode());
    }

    public AuthorizationDecision authorizeData(
            SessionContext subject, String permissionCode, AuthorizationTarget target) {
        Objects.requireNonNull(target, "target");
        if (!Objects.equals(subject.tenantId(), target.tenantId())) {
            return AuthorizationDecision.deny(AuthorizationDecision.Reason.TENANT_MISMATCH, permissionCode, null);
        }
        List<AuthorizationGrant> grants = matchingGrants(subject, permissionCode);
        if (grants.isEmpty()) {
            return AuthorizationDecision.deny(AuthorizationDecision.Reason.NO_PERMISSION, permissionCode, null);
        }
        boolean hasScopedGrant = false;
        for (AuthorizationGrant grant : grants) {
            if (grant.dataScopeCode() == null
                    || grant.dataScopeCode().isBlank()
                    || grant.dataScopeRuleJson() == null
                    || grant.dataScopeRuleJson().isBlank()) {
                continue;
            }
            hasScopedGrant = true;
            if (dataScopes.allows(subject, grant, target)) {
                return AuthorizationDecision.allow(permissionCode, grant.dataScopeCode());
            }
        }
        AuthorizationDecision.Reason reason = hasScopedGrant
                ? AuthorizationDecision.Reason.DATA_SCOPE_DENIED
                : AuthorizationDecision.Reason.DATA_SCOPE_MISSING;
        return AuthorizationDecision.deny(
                reason, permissionCode, grants.getFirst().dataScopeCode());
    }

    private List<AuthorizationGrant> matchingGrants(SessionContext subject, String permissionCode) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(permissionCode, "permissionCode");
        return identities.authorization(subject).grants().stream()
                .filter(grant -> permissionCode.equals(grant.permissionCode()))
                .toList();
    }
}
