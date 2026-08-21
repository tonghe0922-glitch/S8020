package cn.shangjingu.platform.iam.stepup;

public final class FailClosedStepUpAuditSink implements StepUpAuditSink {
    @Override
    public void record(StepUpAuditEvent event) {
        throw new IllegalStateException("Step-Up audit sink is not configured");
    }
}
