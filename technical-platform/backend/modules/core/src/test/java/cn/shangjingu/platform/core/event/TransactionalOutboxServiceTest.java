package cn.shangjingu.platform.core.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionalOutboxServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionalOutboxService service = new TransactionalOutboxService(jdbc);

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void refusesToEnqueueOutsideCallerTransaction() {
        assertThrows(IllegalStateException.class, () -> service.enqueue(command("outside-tx")));
    }

    @Test
    void insertsInsideCallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertNotNull(service.enqueue(command("inside-tx")));
    }

    private static TransactionalOutboxService.Command command(String key) {
        return new TransactionalOutboxService.Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "C2_TEST",
                UUID.randomUUID(),
                "C2_TEST_EVENT",
                1,
                "{\"ok\":true}",
                key);
    }
}
