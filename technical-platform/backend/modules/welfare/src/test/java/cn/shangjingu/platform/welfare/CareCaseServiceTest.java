package cn.shangjingu.platform.welfare;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CareCaseServiceTest {
    @Test
    void rejectsNegativeBenefitBeforePersistence() {
        CareCaseService service = new CareCaseService(null, null, null, null, List.of());
        CareCaseService.CreateCommand command = new CareCaseService.CreateCommand(
                "EMPLOYEE",
                LocalDate.now(),
                "care",
                "reason",
                "NORMAL",
                null,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                new BigDecimal("-0.01"),
                "B01",
                "C01",
                "CNY",
                "CARE",
                Instant.now(),
                "synthetic fact",
                LocalDate.now(),
                "L1",
                0L);
        assertThrows(ProcessRejectedException.class, () -> service.create(actor(), "key", "hash", command));
    }

    @Test
    void financeExecutionFailsClosedWhenCapabilityIsMissing() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        CareCaseService.Repository repository = mock(CareCaseService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DatabaseSecurityContext actor = actor(tenant);
        CareCaseService.CareCase current = new CareCaseService.CareCase(
                id,
                tenant,
                "P016-1",
                "S04",
                3,
                "EMPLOYEE",
                LocalDate.now(),
                "care",
                "reason",
                "NORMAL",
                null,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                "B01",
                "C01",
                "CNY",
                "CARE",
                Instant.now(),
                "synthetic fact",
                LocalDate.now(),
                "L1",
                0L,
                null,
                null,
                null,
                null);
        when(repository.find(tenant, id)).thenReturn(Optional.of(current));
        CareCaseService service = new CareCaseService(
                transactions,
                mock(IdempotencyRegistry.class),
                mock(BusinessNumberService.class),
                repository,
                List.of());
        assertThrows(ProcessRejectedException.class, () -> service.advance(actor, id, 3, "S05", null, null));
    }

    private static DatabaseSecurityContext actor() {
        return actor(UUID.randomUUID());
    }

    private static DatabaseSecurityContext actor(UUID tenant) {
        return new DatabaseSecurityContext(
                tenant,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}
