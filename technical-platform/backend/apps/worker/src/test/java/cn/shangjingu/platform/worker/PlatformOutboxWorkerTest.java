package cn.shangjingu.platform.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformOutboxWorkerTest {
    @Test
    void exponentialBackoffCapsAtConfiguredMaximum() {
        assertEquals(0L, PlatformOutboxWorker.retryDelayMillis(0, 1000, 8000));
        assertEquals(1000L, PlatformOutboxWorker.retryDelayMillis(1, 1000, 8000));
        assertEquals(2000L, PlatformOutboxWorker.retryDelayMillis(2, 1000, 8000));
        assertEquals(8000L, PlatformOutboxWorker.retryDelayMillis(8, 1000, 8000));
    }

    @Test
    void duplicateEventHandlersFailClosed() {
        PlatformOutboxHandler first = handler("same-event", "consumer-a");
        PlatformOutboxHandler second = handler("same-event", "consumer-b");
        assertThrows(IllegalArgumentException.class, () -> new PlatformOutboxWorker(
                mock(TenantTransactionRunner.class), mock(JdbcTemplate.class), mock(PlatformInboxService.class),
                List.of(first, second), 3, Duration.ofMillis(1), Duration.ofSeconds(1)));
    }

    private static PlatformOutboxHandler handler(String eventType, String consumerName) {
        return new PlatformOutboxHandler() {
            @Override public String eventType() { return eventType; }
            @Override public String consumerName() { return consumerName; }
            @Override public void handle(cn.shangjingu.platform.core.event.PlatformOutboxEvent event) {}
        };
    }
}
