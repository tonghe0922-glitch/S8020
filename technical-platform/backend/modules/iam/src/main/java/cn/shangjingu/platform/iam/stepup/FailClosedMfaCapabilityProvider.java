package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.session.SessionContext;

public final class FailClosedMfaCapabilityProvider implements MfaCapabilityProvider {
    @Override
    public boolean verify(
            SessionContext subject,
            short enrolledMfaLevel,
            int requiredMfaLevel,
            String assertion) {
        return false;
    }
}
