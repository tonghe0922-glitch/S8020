package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class LearningServiceCreateTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000110");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000110");
    private static final UUID IDENTITY = UUID.fromString("30000000-0000-0000-0000-000000000110");
    private static final UUID ACTOR_EMPLOYEE = UUID.fromString("40000000-0000-0000-0000-000000000110");
    private static final UUID TARGET_EMPLOYEE = UUID.fromString("41000000-0000-0000-0000-000000000110");
    private static final UUID CENTER = UUID.fromString("50000000-0000-0000-0000-000000000110");
    private static final UUID OTHER_CENTER = UUID.fromString("51000000-0000-0000-0000-000000000110");
    private static final UUID POSITION = UUID.fromString("60000000-0000-0000-0000-000000000110");
    private static final UUID ASSIGNMENT = UUID.fromString("70000000-0000-0000-0000-000000000110");
    private static final UUID WORKFLOW_VERSION = UUID.fromString("80000000-0000-0000-0000-000000000110");
    private static final UUID FORM = UUID.fromString("90000000-0000-0000-0000-000000000110");
    private static final DatabaseSecurityContext ACTOR =
            new DatabaseSecurityContext(TENANT, USER, IDENTITY, ACTOR_EMPLOYEE, null, CENTER, POSITION);

    @Test
    void createPersistsSourceNodeWithServerBusinessNumberWithoutStartingWorkflow() {
        Fixture fixture = new Fixture();
        AtomicReference<LearningService.LearningRecord> inserted = new AtomicReference<>();
        when(fixture.repository.activeEmployeeInCenter(TENANT, TARGET_EMPLOYEE, CENTER))
                .thenReturn(true);
        when(fixture.repository.workflowVersion(TENANT)).thenReturn(Optional.of(WORKFLOW_VERSION));
        when(fixture.repository.form(TENANT)).thenReturn(Optional.of(new LearningService.FormRef(FORM, 1)));
        when(fixture.numbers.next(TENANT, ACTOR_EMPLOYEE, LearningService.PROCESS_CODE))
                .thenReturn("P010-20260812-0001");
        org.mockito.Mockito.doAnswer(invocation -> {
                    inserted.set(invocation.getArgument(0));
                    return null;
                })
                .when(fixture.repository)
                .insert(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        when(fixture.repository.find(TENANT, ASSIGNMENT)).thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
        when(fixture.repository.evidence(TENANT, ASSIGNMENT)).thenReturn(List.of());

        LearningService.Aggregate result =
                fixture.service.create(ACTOR, "create-learning", "request-hash", command(CENTER));

        assertEquals(ASSIGNMENT, result.record().id());
        assertEquals("P010-20260812-0001", result.record().businessNo());
        assertEquals("S01", result.record().currentNodeCode());
        assertEquals(LearningService.label("S01"), result.record().status());
        assertEquals(TARGET_EMPLOYEE, result.record().ownerEmployeeId());
        assertEquals(CENTER, result.record().ownerCenterId());
        assertEquals(BigDecimal.ZERO, result.record().completionRate());
        assertEquals(0, result.record().versionNo());
        verify(fixture.outbox).enqueue(any());
        verify(fixture.workflow, never()).start(any());
        verify(fixture.forms, never()).submit(any());
    }

    @Test
    void createRejectsInactiveOrCrossCenterTargetBeforeBusinessNumberAndInsert() {
        Fixture fixture = new Fixture();
        when(fixture.repository.activeEmployeeInCenter(TENANT, TARGET_EMPLOYEE, CENTER))
                .thenReturn(false);

        ProcessRejectedException inactive = assertThrows(
                ProcessRejectedException.class,
                () -> fixture.service.create(ACTOR, "inactive-target", "request-hash", command(CENTER)));
        assertTrue(inactive.getMessage().contains("not active in owner center"));
        verify(fixture.numbers, never()).next(any(), any(), anyString());
        verify(fixture.repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());

        ProcessRejectedException crossCenter = assertThrows(
                ProcessRejectedException.class,
                () -> fixture.service.create(ACTOR, "cross-center", "request-hash", command(OTHER_CENTER)));
        assertTrue(crossCenter.getMessage().contains("authenticated center"));
    }

    private static LearningService.CreateCommand command(UUID ownerCenterId) {
        return new LearningService.CreateCommand(
                "高风险岗位年度复训",
                "年度资格复证",
                ownerCenterId,
                TARGET_EMPLOYEE,
                "content-v3",
                "安全培训组",
                "COURSE-2026-08",
                "高风险设备岗位",
                "2026-Q3",
                "HIGH",
                Instant.parse("2026-08-13T01:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"));
    }

    private static TenantTransactionRunner directTransactions() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(any(DatabaseSecurityContext.class), ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return transactions;
    }

    private static final class Fixture {
        private final IdempotencyRegistry idempotency = mock(IdempotencyRegistry.class);
        private final BusinessNumberService numbers = mock(BusinessNumberService.class);
        private final TransactionalOutboxService outbox = mock(TransactionalOutboxService.class);
        private final WorkflowRuntimeService workflow = mock(WorkflowRuntimeService.class);
        private final WorkflowTaskAssignmentService tasks = mock(WorkflowTaskAssignmentService.class);
        private final WorkflowFormService forms = mock(WorkflowFormService.class);
        private final LearningService.Repository repository = mock(LearningService.Repository.class);
        private final LearningService service;

        private Fixture() {
            when(idempotency.claim(any(), any(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new IdempotencyClaim(ASSIGNMENT, false));
            service = new LearningService(
                    directTransactions(),
                    idempotency,
                    numbers,
                    outbox,
                    workflow,
                    tasks,
                    forms,
                    repository,
                    new ObjectMapper());
        }
    }
}
