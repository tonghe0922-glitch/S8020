package cn.shangjingu.platform.api.security;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public class SecurityAuditHealthIndicator implements HealthIndicator {
    private final JdbcSecurityAuditService audit;

    public SecurityAuditHealthIndicator(JdbcSecurityAuditService audit) {
        this.audit = audit;
    }

    @Override
    public Health health() {
        boolean available = audit.isAvailable();
        Health.Builder health;
        if (available) {
            health = Health.up();
        } else if (audit.mode() == SecurityAuditMode.FAIL_CLOSED) {
            health = Health.down();
        } else {
            health = Health.up().withDetail("auditStatus", "DEGRADED");
        }
        health.withDetail("mode", audit.mode().configurationValue())
                .withDetail("available", available)
                .withDetail("writeFailureCount", audit.writeFailureCount());
        if (audit.lastFailureAt() != null) {
            health.withDetail("lastFailureAt", audit.lastFailureAt().toString());
        }
        return health.build();
    }
}
