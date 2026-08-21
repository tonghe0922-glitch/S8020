package cn.shangjingu.platform.iam.stepup;

public final class StepUpRejectedException extends RuntimeException {
    public enum Reason {
        ACCOUNT_INACTIVE,
        MFA_LEVEL_INSUFFICIENT,
        MFA_VERIFICATION_FAILED,
        AUDIT_UNAVAILABLE,
        TICKET_CONFLICT,
        TICKET_MISSING_OR_EXPIRED,
        TICKET_REPLAYED,
        TICKET_CONTEXT_MISMATCH
    }

    private final Reason reason;

    public StepUpRejectedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
