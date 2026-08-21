package cn.shangjingu.platform.iam.authorization;

public record FieldAccessDecision(Outcome outcome, AuthorizationDecision authorization) {
    public enum Outcome {
        VISIBLE,
        MASKED,
        DENIED,
        STEP_UP_REQUIRED
    }
}
