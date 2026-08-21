package cn.shangjingu.platform.api.security;

public record RequestAuditContext(String requestId, String remoteAddress, String deviceFingerprint) {
    private static final ThreadLocal<RequestAuditContext> CURRENT = new ThreadLocal<>();

    public static void install(RequestAuditContext context) {
        CURRENT.set(context);
    }

    public static RequestAuditContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
