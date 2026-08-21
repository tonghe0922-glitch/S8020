package cn.shangjingu.platform.api.security;

public final class SecurityAuditUnavailableException extends RuntimeException {
    public SecurityAuditUnavailableException(Throwable cause) {
        super("security audit unavailable", cause);
    }
}
