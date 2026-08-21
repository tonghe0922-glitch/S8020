package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.RedisSessionStore;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityInfrastructureHealthConfiguration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(SecurityInfrastructureHealthConfiguration.class);

    @Bean(name = "sessionStoreHealthIndicator")
    HealthIndicator sessionStoreHealthIndicator(RedisSessionStore store) {
        return () -> {
            try {
                store.verifyAvailable();
                return Health.up()
                        .withDetail("component", "redis-session-store")
                        .build();
            } catch (SessionRejectedException exception) {
                return Health.down(exception)
                        .withDetail("component", "redis-session-store")
                        .withDetail("code", "session_store_unavailable")
                        .build();
            }
        };
    }

    @Bean(name = "securityAuditHealthIndicator")
    HealthIndicator securityAuditHealthIndicator(JdbcSecurityAuditService audit) {
        return () -> {
            boolean available = audit.isAvailable();
            Health.Builder builder;
            if (available || audit.mode().isFailOpen()) {
                builder = Health.up();
            } else {
                builder = Health.down();
            }
            return builder
                    .withDetail("component", "security-audit-database")
                    .withDetail("available", available)
                    .withDetail("mode", audit.mode().name().toLowerCase().replace('_', '-'))
                    .withDetail("failedWriteCount", audit.failedWriteCount())
                    .withDetail(
                            "state",
                            available
                                    ? "available"
                                    : audit.mode().isFailOpen()
                                            ? "degraded-fail-open"
                                            : "unavailable-fail-closed")
                    .build();
        };
    }

    @Bean
    ApplicationRunner securityInfrastructurePreflight(
            RedisSessionStore sessionStore,
            JdbcSecurityAuditService audit) {
        return arguments -> {
            try {
                sessionStore.verifyAvailable();
                LOGGER.info("Security infrastructure preflight: Redis session store is available");
            } catch (SessionRejectedException exception) {
                LOGGER.warn(
                        "Security infrastructure preflight: Redis session store is unavailable; "
                                + "login issuance will return session_store_unavailable");
            }

            if (audit.isAvailable()) {
                LOGGER.info(
                        "Security infrastructure preflight: audit database is available; mode={}",
                        audit.mode());
            } else {
                LOGGER.warn(
                        "Security infrastructure preflight: audit database is unavailable; mode={}; "
                                + "failedWriteCount={}",
                        audit.mode(),
                        audit.failedWriteCount());
            }
        };
    }
}
