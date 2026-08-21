package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.SessionContext;

public record SessionPrincipal(String accessToken, SessionContext context) {
    @Override
    public String toString() {
        return "SessionPrincipal[identityId=" + context.identityId() + ", appointmentId=" + context.appointmentId() + "]";
    }
}
