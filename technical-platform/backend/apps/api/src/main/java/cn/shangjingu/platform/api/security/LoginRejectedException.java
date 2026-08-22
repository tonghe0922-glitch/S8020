package cn.shangjingu.platform.api.security;

public final class LoginRejectedException extends RuntimeException {
    public enum Reason {
        INVALID_CREDENTIALS,
        NO_ACTIVE_IDENTITY,
        MFA_REQUIRED_OR_INVALID
    }

    private final Reason reason;

    public LoginRejectedException(Reason reason) {
        super("authentication rejected");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
