package cn.shangjingu.platform.iam.stepup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.SessionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepUpMinimumMfaTest {
    @Test
    void highRiskConsumeRejectsTicketBelowMinimumMfaLevel() {
        StepUpTicketStore store = mock(StepUpTicketStore.class);
        StepUpAuditSink audit = mock(StepUpAuditSink.class);
        SessionContext subject = subject();
        when(store.consume(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(subject),
                        org.mockito.ArgumentMatchers.eq("platform.file.download"),
                        org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(StepUpTicketStore.ConsumeOutcome.MFA_LEVEL_INSUFFICIENT);
        StepUpService service = new StepUpService(
                mock(IdentityDirectoryService.class),
                store,
                new StepUpPolicy(Duration.ofMinutes(5)),
                mock(MfaCapabilityProvider.class),
                audit);

        StepUpRejectedException rejected = assertThrows(
                StepUpRejectedException.class,
                () -> service.requireAndConsume("synthetic-ticket", subject, "platform.file.download", 2));
        assertEquals(StepUpRejectedException.Reason.MFA_LEVEL_INSUFFICIENT, rejected.reason());
        verify(store)
                .consume(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(subject),
                        org.mockito.ArgumentMatchers.eq("platform.file.download"),
                        org.mockito.ArgumentMatchers.eq(2));
        verify(audit)
                .record(org.mockito.ArgumentMatchers.argThat(event -> "STEP_UP_REJECTED".equals(event.eventType())
                        && "MFA_LEVEL_INSUFFICIENT".equals(event.outcome())));
    }

    private static SessionContext subject() {
        return new SessionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now());
    }
}
