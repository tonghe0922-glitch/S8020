package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PerformanceServiceMappingTest {
    @Test
    void createMapsOnlyP011FieldsIntoNormalizedPayload() {
        Phase11LifecycleService lifecycle = mock(Phase11LifecycleService.class);
        PerformanceService service = new PerformanceService(lifecycle);
        DatabaseSecurityContext actor = context();
        UUID center = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        PerformanceService.CreateCommand command = new PerformanceService.CreateCommand(
                "2026 Q3 performance",
                "quarterly cycle",
                "NORMAL",
                "NORMAL",
                center,
                employee,
                LocalDate.of(2026, 7, 1),
                Instant.parse("2026-07-01T00:00:00Z"),
                "delivery and service targets",
                "P011-CONTENT-V1",
                "2026-Q3");
        when(lifecycle.create(any(), eq(Phase11Process.P011), any(), any(), any()))
                .thenReturn(mock(Phase11Record.class));

        service.create(actor, "p011-create-1", "hash", command);

        ArgumentCaptor<Phase11CreateData> payload = ArgumentCaptor.forClass(Phase11CreateData.class);
        verify(lifecycle)
                .create(eq(actor), eq(Phase11Process.P011), eq("p011-create-1"), eq("hash"), payload.capture());
        assertEquals("delivery and service targets", payload.getValue().factSummary());
        assertEquals("2026-Q3", payload.getValue().periodNo());
        assertEquals(employee, payload.getValue().ownerEmployeeId());
        assertEquals(center, payload.getValue().ownerCenterId());
    }

    @Test
    void actionDoesNotAcceptClientTargetStatus() {
        Phase11LifecycleService lifecycle = mock(Phase11LifecycleService.class);
        PerformanceService service = new PerformanceService(lifecycle);
        DatabaseSecurityContext actor = context();
        UUID cycle = UUID.randomUUID();
        PerformanceService.ActionCommand command =
                new PerformanceService.ActionCommand(3, "facts collected", "verified", false, null, null);
        when(lifecycle.act(any(), eq(Phase11Process.P011), any(), any(), any(), any(), any()))
                .thenReturn(mock(Phase11Record.class));

        service.act(actor, cycle, "COLLECT_FACTS", "p011-action-1", "hash", command);

        ArgumentCaptor<Phase11ActionData> payload = ArgumentCaptor.forClass(Phase11ActionData.class);
        verify(lifecycle)
                .act(
                        eq(actor),
                        eq(Phase11Process.P011),
                        eq(cycle),
                        eq("COLLECT_FACTS"),
                        eq("p011-action-1"),
                        eq("hash"),
                        payload.capture());
        assertEquals(3, payload.getValue().expectedVersion());
        assertEquals("facts collected", payload.getValue().summary());
    }

    private static DatabaseSecurityContext context() {
        return new DatabaseSecurityContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }
}
