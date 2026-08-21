package cn.shangjingu.platform.iam.session;

public final class SessionRejectedException extends RuntimeException {
    public enum Reason {
        INVALID_ACCESS,
        INVALID_REFRESH,
        REFRESH_REPLAY,
        IDENTITY_INACTIVE,
        APPOINTMENT_INACTIVE,
        SESSION_CONFLICT,
        STORE_UNAVAILABLE
    }

    private final Reason reason;

    public SessionRejectedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public SessionRejectedException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
