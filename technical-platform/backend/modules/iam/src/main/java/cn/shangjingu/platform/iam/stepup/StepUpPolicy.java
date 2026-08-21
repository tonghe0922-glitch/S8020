package cn.shangjingu.platform.iam.stepup;

import java.time.Duration;
import java.util.Objects;

public record StepUpPolicy(Duration ticketTtl) {
    public StepUpPolicy {
        Objects.requireNonNull(ticketTtl, "ticketTtl");
        if (ticketTtl.isZero() || ticketTtl.isNegative()) {
            throw new IllegalArgumentException("ticketTtl must be positive");
        }
    }
}
