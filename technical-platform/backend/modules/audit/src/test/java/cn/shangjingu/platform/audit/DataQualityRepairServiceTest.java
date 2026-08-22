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

class DataQualityRepairServiceTest {
    @Test
    void sameReviewerAndExecutorIsRejectedFromPersistedApproval() {
        TenantTransactionRunner transactions = transactions();
        DataQualityRepairService.Repository repository = mock(DataQualityRepairService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.find(tenant, id)).thenReturn(Optional.of(issue(tenant, id, "S05", 4)));
        when(repository.repairControl(tenant, id)).thenReturn(new DataQualityRepairService.RepairControl(user, "{}"));
        DataQualityRepairService service =
                service(transactions, repository, List.of(), List.of(), List.of(), List.of());
        assertThrows(ProcessRejectedException.class, () -> service.executeRepair(actor(tenant, user), id, 4));
    }

    @Test
    void missingRepairHandlerFailsClosed() {
        TenantTransactionRunner transactions = transactions();
        DataQualityRepairService.Repository repository = mock(DataQualityRepairService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        UUID executor = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.find(tenant, id)).thenReturn(Optional.of(issue(tenant, id, "S05", 4)));
        when(repository.repairControl(tenant, id))
                .thenReturn(new DataQualityRepairService.RepairControl(reviewer, "{}"));
        DataQualityRepairService service =
                service(transactions, repository, List.of(), List.of(), List.of(), List.of());
        assertThrows(ProcessRejectedException.class, () -> service.executeRepair(actor(tenant, executor), id, 4));
    }

    private static DataQualityRepairService service(
            TenantTransactionRunner transactions,
            DataQualityRepairService.Repository repository,
            List<DataQualityRepairService.RepairGovernanceCapability> governance,
            List<DataQualityRepairService.RepairHandler> handlers,
            List<DataQualityRepairService.CompensationCapability> compensations,
            List<DataQualityRepairService.VerificationCapability> verifiers) {
        return new DataQualityRepairService(
                transactions,
                mock(IdempotencyRegistry.class),
                mock(BusinessNumberService.class),
                repository,
                new ObjectMapper(),
                governance,
                handlers,
                compensations,
                verifiers);
    }

    private static TenantTransactionRunner transactions() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return transactions;
    }

    private static DataQualityRepairService.QualityIssue issue(UUID tenant, UUID id, String status, int version) {
        return new DataQualityRepairService.QualityIssue(
                id,
                tenant,
                "P020-1",
                status,
                version,
                "RULE",
                "SYNTHETIC_OBJECT",
                UUID.randomUUID(),
                "QUALITY",
                "HIGH",
                "{\"before\":1}",
                null,
                null,
                null,
                null,
                Instant.now(),
                null,
                LocalDate.now(),
                "QUALITY_REPAIR",
                "TEST",
                "synthetic issue",
                "synthetic-service",
                "synthetic scope",
                "HIGH",
                UUID.randomUUID());
    }

    private static DatabaseSecurityContext actor(UUID tenant, UUID user) {
        return new DatabaseSecurityContext(
                tenant,
                user,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}
