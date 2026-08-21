package cn.shangjingu.platform.iam.mfa;

import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.stepup.MfaCapabilityProvider;
import org.springframework.stereotype.Component;

@Component
public final class TotpMfaCapabilityProvider implements MfaCapabilityProvider {
    private final TotpCredentialService totp;
    public TotpMfaCapabilityProvider(TotpCredentialService totp){this.totp=totp;}
    @Override public boolean verify(SessionContext subject, short enrolledMfaLevel, int requiredMfaLevel, String assertion){
        if(subject==null || enrolledMfaLevel<requiredMfaLevel || requiredMfaLevel<=0) return false;
        return totp.verifyAssertion(subject.tenantId(),subject.userId(),assertion);
    }
}
