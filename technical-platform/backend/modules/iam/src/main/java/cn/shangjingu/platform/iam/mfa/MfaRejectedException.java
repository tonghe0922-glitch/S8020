package cn.shangjingu.platform.iam.mfa;

public final class MfaRejectedException extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        ASSERTION_REJECTED,
        CONFLICT,
        UNAVAILABLE
    }

    private final Reason reason;

    public MfaRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MfaRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public MfaRejectedException(String message) {
        this(Reason.UNAVAILABLE, message);
    }

    public MfaRejectedException(String message, Throwable cause) {
        this(Reason.UNAVAILABLE, message, cause);
    }

    public Reason reason() {
        return reason;
    }
}
