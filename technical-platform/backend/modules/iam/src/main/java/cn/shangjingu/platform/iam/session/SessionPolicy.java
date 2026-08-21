package cn.shangjingu.platform.iam.session;

import java.time.Duration;
import java.util.Objects;

public record SessionPolicy(Duration accessTtl, Duration refreshTtl) {
    public SessionPolicy {
        Objects.requireNonNull(accessTtl, "accessTtl");
        Objects.requireNonNull(refreshTtl, "refreshTtl");
        if (accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalArgumentException("accessTtl must be positive");
        }
        if (refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalArgumentException("refreshTtl must be greater than accessTtl");
        }
    }
}
