package cn.shangjingu.platform.audit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SensitiveExportServiceTest {
    @Test
    void downloadFailsClosedWithoutAuditedDeliveryCapability() {
        TenantTransactionRunner transactions = transactions();
        SensitiveExportService.Repository repository = mock(SensitiveExportService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SensitiveExportService.ExportRequest request = request(tenant, id, "S06", 5);
        when(repository.find(tenant, id)).thenReturn(Optional.of(request));
        SensitiveExportService service = new SensitiveExportService(
                transactions,
                mock(IdempotencyRegistry.class),
                mock(BusinessNumberService.class),
                repository,
                new ObjectMapper(),
                List.of(),
                List.of());
        assertThrows(ProcessRejectedException.class, () -> service.issueAuditedDownload(actor(tenant), id, 5));
    }

    @Test
    void generationResultCannotBeInsertedFromWrongState() {
        TenantTransactionRunner transactions = transactions();
        SensitiveExportService.Repository repository = mock(SensitiveExportService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.find(tenant, id)).thenReturn(Optional.of(request(tenant, id, "S04", 3)));
        SensitiveExportService service = new SensitiveExportService(
                transactions,
                mock(IdempotencyRegistry.class),
                mock(BusinessNumberService.class),
                repository,
                new ObjectMapper(),
                List.of(),
                List.of());
        assertThrows(
                ProcessRejectedException.class,
                () -> service.recordGenerated(
                        actor(tenant),
                        id,
                        3,
                        new SensitiveExportService.GenerationResult(
                                UUID.randomUUID(), "watermark", Instant.now().plusSeconds(300))));
    }

    private static TenantTransactionRunner transactions() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return transactions;
    }

    private static SensitiveExportService.ExportRequest request(UUID tenant, UUID id, String status, int version) {
        return new SensitiveExportService.ExportRequest(
                id,
                tenant,
                "P019-1",
                status,
                version,
                "SYNTHETIC",
                "{}",
                "{}",
                "test",
                "L1",
                "wm",
                Instant.now().plusSeconds(600),
                UUID.randomUUID(),
                0,
                Instant.now(),
                null,
                LocalDate.now(),
                "TEST",
                "synthetic export",
                null,
                "synthetic-service",
                "synthetic scope",
                "LOW",
                UUID.randomUUID());
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
