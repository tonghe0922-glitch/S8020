package cn.shangjingu.platform.iam.stepup;

@FunctionalInterface
public interface StepUpAuditSink {
    void record(StepUpAuditEvent event);
}
