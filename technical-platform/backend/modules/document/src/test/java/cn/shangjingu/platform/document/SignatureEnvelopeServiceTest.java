package cn.shangjingu.platform.document;

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

class SignatureEnvelopeServiceTest {
    @Test
    void initiationFailsClosedWithoutExactlyOneProvider() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        SignatureEnvelopeService.Repository repository = mock(SignatureEnvelopeService.Repository.class);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SignatureEnvelopeService.Envelope envelope = new SignatureEnvelopeService.Envelope(
                id,
                tenant,
                "P017-1",
                "P017-1",
                "S03",
                2,
                "a".repeat(64),
                "v1",
                "SEQUENTIAL",
                Instant.now().plusSeconds(3600),
                "PENDING",
                null,
                "MFA",
                LocalDate.now(),
                "AGREEMENT",
                "synthetic envelope",
                Instant.now(),
                null,
                UUID.randomUUID());
        when(repository.find(tenant, id)).thenReturn(Optional.of(envelope));
        SignatureEnvelopeService service = new SignatureEnvelopeService(
                transactions,
                mock(IdempotencyRegistry.class),
                mock(BusinessNumberService.class),
                repository,
                mock(SignatureEnvelopeService.FileEvidenceCapability.class),
                List.of());
        assertThrows(ProcessRejectedException.class, () -> service.advance(actor(tenant), id, 2, "S04"));
    }

    @Test
    void incompleteCallbackEvidenceIsRejectedBeforeMutation() {
        SignatureEnvelopeService service = new SignatureEnvelopeService(null, null, null, null, null, List.of());
        SignatureEnvelopeService.CallbackEvidence evidence =
                new SignatureEnvelopeService.CallbackEvidence(null, null, null, null, null, null, List.of());
        assertThrows(
                ProcessRejectedException.class,
                () -> service.verifyCallback(
                        actor(UUID.randomUUID()), UUID.randomUUID(), 1, "event-1", "hash", evidence));
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
