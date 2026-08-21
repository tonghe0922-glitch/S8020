package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.session.SessionContext;

@FunctionalInterface
public interface MfaCapabilityProvider {
    boolean verify(
            SessionContext subject,
            short enrolledMfaLevel,
            int requiredMfaLevel,
            String assertion);
}
