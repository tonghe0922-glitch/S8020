package cn.shangjingu.platform.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DataImportServiceTest {
    @Test
    void resultReportStateCannotBeEnteredSynchronously() {
        TenantTransactionRunner transactions = transactions();
        DataImportService.Repository repository = mock(DataImportService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DataImportService.DataImportJob job = job(tenant, id, "S08", 7);
        when(repository.find(tenant, id)).thenReturn(Optional.of(job));
        DataImportService service = new DataImportService(
                transactions, mock(IdempotencyRegistry.class), mock(BusinessNumberService.class), repository, List.of());
        assertThrows(ProcessRejectedException.class,
                () -> service.advance(actor(tenant), id, 7, "S09"));
    }

    @Test
    void validationFailsClosedWithoutExactlyOneValidator() {
        TenantTransactionRunner transactions = transactions();
        DataImportService.Repository repository = mock(DataImportService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DataImportService.DataImportJob job = job(tenant, id, "S02", 1);
        when(repository.find(tenant, id)).thenReturn(Optional.of(job));
        DataImportService service = new DataImportService(
                transactions, mock(IdempotencyRegistry.class), mock(BusinessNumberService.class), repository, List.of());
        assertThrows(ProcessRejectedException.class,
                () -> service.advance(actor(tenant), id, 1, "S03"));
    }

    private static TenantTransactionRunner transactions() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return transactions;
    }

    private static DataImportService.DataImportJob job(UUID tenant, UUID id, String status, int version) {
        return new DataImportService.DataImportJob(
                id, tenant, "P018-1", status, version, "SYNTHETIC", UUID.randomUUID(), "v1",
                0, 0, 0, null, null, Instant.now(), null, LocalDate.now(), "TEST",
                "synthetic import", "rollback", "synthetic-service", "synthetic scope", "LOW", UUID.randomUUID());
    }

    private static DatabaseSecurityContext actor(UUID tenant) {
        return new DatabaseSecurityContext(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
