package cn.shangjingu.platform.workflow.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Phase11LifecycleServiceTest {
    private TenantTransactionRunner transactions;
    private IdempotencyRegistry idempotency;
    private BusinessNumberService numbers;
    private TransactionalOutboxService outbox;
    private Phase11WorkflowCoordinator workflow;
    private Phase11Repository repository;
    private Phase11LifecycleService service;

    @BeforeEach
    void setUp() {
        transactions = mock(TenantTransactionRunner.class);
        idempotency = mock(IdempotencyRegistry.class);
        numbers = mock(BusinessNumberService.class);
        outbox = mock(TransactionalOutboxService.class);
        workflow = mock(Phase11WorkflowCoordinator.class);
        repository = mock(Phase11Repository.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        service = new Phase11LifecycleService(
                transactions, idempotency, numbers, outbox, workflow, repository, new ObjectMapper());
    }

    @Test
    void createPersistsCanonicalRecordBindsWorkflowAndEmitsOutbox() {
        DatabaseSecurityContext actor = context();
        UUID recordId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        Phase11CreateData data = createData(actor.orgId(), actor.employeeId());
        when(repository.activeEmployeeInOrg(actor.tenantId(), actor.orgId(), actor.employeeId()))
                .thenReturn(true);
        when(idempotency.claim(
                        eq(actor.tenantId()),
                        eq(actor.employeeId()),
                        eq("p011-create"),
                        eq("hash"),
                        eq("performance.performance_cycle"),
                        any(UUID.class),
                        any()))
                .thenReturn(new IdempotencyClaim(recordId, false));
        when(numbers.next(actor.tenantId(), actor.employeeId(), "P011")).thenReturn("P011-202608150001");
        when(workflow.start(eq(actor), eq(Phase11Process.P011), any(), eq(data), eq("p011-create")))
                .thenReturn(new Phase11WorkflowCoordinator.Started(workflowId, "S02"));
        when(repository.bindWorkflow(
                        eq(Phase11Process.P011),
                        eq(actor.tenantId()),
                        eq(recordId),
                        eq(0),
                        eq(workflowId),
                        eq("S02"),
                        eq("员工确认"),
                        eq(actor.employeeId())))
                .thenReturn(1);
        Phase11Record created = record(recordId, actor, workflowId, "S02", 1);
        when(repository.find(Phase11Process.P011, actor.tenantId(), recordId)).thenReturn(Optional.of(created));

        Phase11Record result = service.create(actor, Phase11Process.P011, "p011-create", "hash", data);

        assertEquals(created, result);
        ArgumentCaptor<Phase11Record> inserted = ArgumentCaptor.forClass(Phase11Record.class);
        verify(repository).insert(eq(Phase11Process.P011), inserted.capture(), eq(data), eq(actor.employeeId()));
        assertEquals(recordId, inserted.getValue().id());
        assertEquals("S01", inserted.getValue().currentNodeCode());
        verify(outbox).enqueue(any(TransactionalOutboxService.Command.class));
    }

    @Test
    void ownerCannotExecuteManagerOrSpecialistAction() {
        DatabaseSecurityContext actor = context();
        UUID cycleId = UUID.randomUUID();
        Phase11Record current = record(cycleId, actor, UUID.randomUUID(), "S07", 5);
        when(repository.find(Phase11Process.P011, actor.tenantId(), cycleId)).thenReturn(Optional.of(current));
        when(idempotency.claim(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyClaim(cycleId, false));

        assertThrows(
                ProcessRejectedException.class,
                () -> service.act(
                        actor,
                        Phase11Process.P011,
                        cycleId,
                        "CALIBRATE",
                        "p011-calibrate",
                        "hash",
                        new Phase11ActionData(5, null, null, null, null, null, null)));
        verify(workflow, never()).advance(any(), any(), any(), any(), any(), any());
    }

    @Test
    void scoreSourcesAreBoundedAndRoleSeparated() {
        DatabaseSecurityContext owner = context();
        UUID cycleId = UUID.randomUUID();
        Phase11Record current = record(cycleId, owner, UUID.randomUUID(), "S05", 3);
        when(repository.find(Phase11Process.P011, owner.tenantId(), cycleId)).thenReturn(Optional.of(current));
        when(idempotency.claim(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdempotencyClaim(cycleId, false));

        assertThrows(
                ProcessRejectedException.class,
                () -> service.submitPerformanceScore(
                        owner, cycleId, "SUPERVISOR", 850, "manager evidence", 3, "p011-supervisor", "hash"));
        assertThrows(
                ProcessRejectedException.class,
                () -> service.submitPerformanceScore(
                        owner, cycleId, "EMPLOYEE", 1001, "employee evidence", 3, "p011-employee", "hash"));
        verify(repository, never()).submitPerformanceScore(any(), any(), anyInt(), any(), anyLong(), any(), any());
    }

    private static Phase11CreateData createData(UUID centerId, UUID employeeId) {
        return new Phase11CreateData(
                "2026 Q3 performance",
                "quarterly cycle",
                "NORMAL",
                "NORMAL",
                centerId,
                employeeId,
                LocalDate.of(2026, 7, 1),
                Instant.parse("2026-07-01T00:00:00Z"),
                "service and delivery goals",
                "P011-CONTENT-V1",
                "2026-Q3");
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

    private static Phase11Record record(
            UUID id, DatabaseSecurityContext actor, UUID workflowId, String node, int version) {
        return new Phase11Record(
                id,
                actor.tenantId(),
                "P011",
                "P011-TEST",
                workflowId,
                "WFI-TEST",
                node,
                Phase11Process.P011.labelFor(node),
                version,
                "performance",
                "reason",
                "NORMAL",
                "NORMAL",
                actor.orgId(),
                actor.employeeId(),
                LocalDate.of(2026, 7, 1),
                Instant.parse("2026-07-01T00:00:00Z"),
                "facts",
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                new ObjectMapper().createObjectNode());
    }
}
