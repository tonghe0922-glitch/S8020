package cn.shangjingu.platform.iam.stepup;

import java.time.Instant;

public record StepUpTicket(
        String ticket,
        String purpose,
        int requiredMfaLevel,
        Instant expiresAt) {
}
