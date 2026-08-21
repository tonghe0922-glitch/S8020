package cn.shangjingu.platform.iam.authorization;

import cn.shangjingu.platform.iam.session.SessionContext;
import org.springframework.stereotype.Service;

@Service
public final class FieldAccessService {
    private final AuthorizationService authorization;

    public FieldAccessService(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    public FieldAccessDecision decide(
            SessionContext subject,
            String requiredPermissionCode,
            AuthorizationTarget target,
            FieldSensitivity sensitivity,
            boolean stepUpSatisfied) {
        AuthorizationDecision decision = authorization.authorizeData(subject, requiredPermissionCode, target);
        if (!decision.allowed()) {
            FieldAccessDecision.Outcome outcome = sensitivity == FieldSensitivity.P2
                    ? FieldAccessDecision.Outcome.MASKED
                    : FieldAccessDecision.Outcome.DENIED;
            return new FieldAccessDecision(outcome, decision);
        }
        if (sensitivity == FieldSensitivity.P3 && !stepUpSatisfied) {
            return new FieldAccessDecision(FieldAccessDecision.Outcome.STEP_UP_REQUIRED, decision);
        }
        return new FieldAccessDecision(FieldAccessDecision.Outcome.VISIBLE, decision);
    }
}
