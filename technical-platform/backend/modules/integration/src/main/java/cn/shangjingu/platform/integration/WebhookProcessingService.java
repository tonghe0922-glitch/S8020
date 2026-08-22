package cn.shangjingu.platform.integration;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Worker-side state transition boundary for persisted webhook evidence. */
public final class WebhookProcessingService {
    private final JdbcTemplate jdbc;

    public WebhookProcessingService(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc is required");
        this.jdbc = jdbc;
    }

    public void markProcessed(UUID tenantId, UUID eventId) {
        requireTransaction();
        int updated = jdbc.update(
                "update integration.webhook_event set processing_status='PROCESSED',processed_at=now(),error_code=null,error_message=null where tenant_id=? and id=? and processing_status='RECEIVED'",
                tenantId,
                eventId);
        if (updated != 1) throw new IllegalStateException("webhook processed transition conflict");
    }

    public void markFailed(UUID tenantId, UUID eventId, String errorCode, String errorMessage) {
        requireTransaction();
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 64)
            throw new IllegalArgumentException("errorCode is invalid");
        int updated = jdbc.update(
                "update integration.webhook_event set processing_status='FAILED',processed_at=now(),error_code=?,error_message=? where tenant_id=? and id=? and processing_status='RECEIVED'",
                errorCode,
                errorMessage,
                tenantId,
                eventId);
        if (updated != 1) throw new IllegalStateException("webhook failed transition conflict");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("webhook processing requires active tenant transaction");
    }
}
