package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.notification.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Moves due scheduled messages into the transactional Outbox without treating retry backoff as a scheduler. */
public final class NotificationDueMessagePump {
    private final JdbcTemplate jdbc;
    private final TenantTransactionRunner transactions;
    private final NotificationService notifications;
    private final int batchSize;

    public NotificationDueMessagePump(JdbcTemplate jdbc, TenantTransactionRunner transactions,
                                      NotificationService notifications, int batchSize) {
        if (jdbc == null || transactions == null || notifications == null || batchSize <= 0) {
            throw new IllegalArgumentException("notification due pump dependencies are required");
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.notifications = notifications;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.notification.worker.poll-ms:1000}")
    public void pump() {
        List<UUID> tenants = jdbc.query("select id from core.tenant where status='ACTIVE' order by id",
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        for (UUID tenantId : tenants) {
            try {
                transactions.required(tenantId, () -> notifications.enqueueDue(tenantId, null, batchSize));
            } catch (RuntimeException ignored) {
                // One tenant must not stop due-message scheduling for other tenants; next poll retries from DB truth.
            }
        }
    }
}
