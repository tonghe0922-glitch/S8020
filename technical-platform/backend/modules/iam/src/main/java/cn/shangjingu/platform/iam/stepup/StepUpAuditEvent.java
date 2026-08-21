package cn.shangjingu.platform.iam.stepup;

import cn.shangjingu.platform.iam.session.SessionContext;
import java.time.Instant;

public record StepUpAuditEvent(
        SessionContext subject,
        String eventType,
        String purpose,
        String outcome,
        Instant occurredAt) {
}
