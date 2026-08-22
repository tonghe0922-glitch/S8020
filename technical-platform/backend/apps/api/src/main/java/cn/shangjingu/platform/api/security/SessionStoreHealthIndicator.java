package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.session.RedisSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

public class SessionStoreHealthIndicator implements HealthIndicator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStoreHealthIndicator.class);

    private final RedisSessionStore store;

    public SessionStoreHealthIndicator(RedisSessionStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyAtStartup() {
        if (!store.isAvailable()) {
            LOGGER.warn("Session storage preflight failed: Redis is unavailable; "
                    + "login and authenticated requests will return session_store_unavailable");
        }
    }

    @Override
    public Health health() {
        boolean available = store.isAvailable();
        Health.Builder builder = available ? Health.up() : Health.down();
        return builder.withDetail("available", available)
                .withDetail("dependency", "redis")
                .build();
    }
}
