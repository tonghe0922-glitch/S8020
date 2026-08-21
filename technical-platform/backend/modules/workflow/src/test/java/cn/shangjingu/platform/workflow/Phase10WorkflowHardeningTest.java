package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

/** Additional executable guards discovered during PHASE-10 post-green business review. */
class Phase10WorkflowHardeningTest {
    private static final UUID TENANT =
            UUID.fromString("11000000-0000-0000-0000-000000000010");
    private static final UUID USER =
            UUID.fromString("21000000-0000-0000-0000-000000000010");
    private static final UUID IDENTITY =
            UUID.fromString("31000000-0000-0000-0000-000000000010");
    private static final UUID EMPLOYEE =
            UUID.fromString("41000000-0000-0000-0000-000000000010");
    private static final UUID MANAGER =
            UUID.fromString("42000000-0000-0000-0000-000000000010");
    private static final UUID CENTER =
            UUID.fromString("51000000-0000-0000-0000-000000000010");
    private static final UUID POSITION =
            UUID.fromString("61000000-0000-0000-0000-000000000010");
    private static final UUID REQUEST =
            UUID.fromString("71000000-0000-0000-0000-000000000010");
    private static final UUID WORKFLOW =
            UUID.fromString("81000000-0000-0000-0000-000000000010");
    private static final UUID DEFINITION =
            UUID.fromString("a1000000-0000-0000-0000-000000000010");
    private static final UUID VERSION =
            UUID.fromString("b1000000-0000-0000-0000-000000000010");
    private static final UUID FORM =
            UUID.fromString("c1000000-0000-0000-0000-000000000010");

    private static final Instant START =
            Instant.parse("2026-08-12T01:00:00Z");
    private static final Instant END =
            Instant.parse("2026-08-12T09:00:00Z");

    private static final DatabaseSecurityContext ACTOR =
            new DatabaseSecurityContext(
                    TENANT,
                    USER,
                    IDENTITY,
                    EMPLOYEE,
                    null,
                    CENTER,
                    POSITION);

    @Test
    void p007EmployeeCanCreateOwnShiftChangeWithoutManagerRole() {
        ShiftChangeService.Repository repository =
                mock(ShiftChangeService.Repository.class);
        BusinessNumberService numbers = mock(BusinessNumberService.class);
        WorkflowRuntimeService workflow =
                mock(WorkflowRuntimeService.class);
        WorkflowFormService forms = mock(WorkflowFormService.class);

        when(repository.isActiveEmployeeInOrg(
                        TENANT, CENTER, EMPLOYEE))
                .thenReturn(true);
        when(repository.workflowVersion(TENANT))
                .thenReturn(Optional.of(VERSION));
        when(repository.form(TENANT))
                .thenReturn(
                        Optional.of(
                                new ShiftChangeService.FormRef(FORM, 1)));
        when(repository.permissionCandidates(
                        TENANT,
                        ShiftChangeService.MANAGE_PERMISSION,
                        CENTER))
                .thenReturn(List.of(MANAGER));
        when(numbers.next(TENANT, EMPLOYEE, "P007"))
                .thenReturn("P007-SELF-001");
        when(workflow.start(
                        any(WorkflowRuntimeService.StartCommand.class)))
                .thenReturn(runtime("S01", "P007"));
        when(workflow.act(
                        any(WorkflowRuntimeService.ActionCommand.class)))
                .thenReturn(runtime("S02", "P007"));
        when(repository.bindAndMove(
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        anyString(),
                        any()))
                .thenReturn(1);
        when(repository.find(TENANT, REQUEST))
                .thenReturn(
                        Optional.of(
                                shiftRecord(
                                        "S02",
                                        1,
                                        "SHIFT_CHANGE")));

        ShiftChangeService service =
                shiftService(repository, numbers, workflow, forms);
        ShiftChangeService.Aggregate result =
                service.create(
                        ACTOR,
                        "p007-self-create",
                        "hash",
                        new ShiftChangeService.CreateCommand(
                                "我的换班申请",
                                null,
                                CENTER,
                                EMPLOYEE,
                                "SHIFT_CHANGE",
                                "家庭安排",
                                "DAY",
                                "2026-08-12",
                                START,
                                END));

        assertEquals("S02", result.record().currentNodeCode());
        ArgumentCaptor<ShiftChangeService.ShiftRecord> inserted =
                ArgumentCaptor.forClass(
                        ShiftChangeService.ShiftRecord.class);
        verify(repository).insert(inserted.capture(), any());
        assertEquals("SHIFT_CHANGE", inserted.getValue().changeAction());
        verify(workflow)
                .start(any(WorkflowRuntimeService.StartCommand.class));
    }

    @Test
    void p007EmployeeCannotDisguiseScheduleCreationAsSelfService() {
        ShiftChangeService.Repository repository =
                mock(ShiftChangeService.Repository.class);
        when(repository.isActiveEmployeeInOrg(
                        TENANT, CENTER, EMPLOYEE))
                .thenReturn(true);
        when(repository.workflowVersion(TENANT))
                .thenReturn(Optional.of(VERSION));
        when(repository.form(TENANT))
                .thenReturn(
                        Optional.of(
                                new ShiftChangeService.FormRef(FORM, 1)));
        when(repository.permissionCandidates(
                        TENANT,
                        ShiftChangeService.MANAGE_PERMISSION,
                        CENTER))
                .thenReturn(List.of(MANAGER));

        ShiftChangeService service =
                shiftService(
                        repository,
                        mock(BusinessNumberService.class),
                        mock(WorkflowRuntimeService.class),
                        mock(WorkflowFormService.class));

        ProcessRejectedException error =
                assertThrows(
                        ProcessRejectedException.class,
                        () ->
                                service.create(
                                        ACTOR,
                                        "p007-forged-schedule",
                                        "hash",
                                        new ShiftChangeService.CreateCommand(
                                                "伪造排班",
                                                null,
                                                CENTER,
                                                EMPLOYEE,
                                                "SCHEDULE",
                                                "not allowed",
                                                "DAY",
                                                "2026-08-12",
                                                START,
                                                END)));

        assertTrue(error.getMessage().contains("eligible manager"));
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void p007ValidationFailsClosedOnLeaveOrOvertimeConflict() {
        ShiftChangeService.Repository repository =
                mock(ShiftChangeService.Repository.class);
        when(repository.find(TENANT, REQUEST))
                .thenReturn(
                        Optional.of(
                                shiftRecord(
                                        "S03",
                                        2,
                                        "SCHEDULE")));
        when(repository.isActiveEmployeeInOrg(
                        TENANT, CENTER, EMPLOYEE))
                .thenReturn(true);
        when(repository.hasOverlappingShift(
                        TENANT,
                        EMPLOYEE,
                        START,
                        END,
                        REQUEST))
                .thenReturn(false);
        when(repository.hasAttendanceConflict(
                        TENANT, EMPLOYEE, START, END))
                .thenReturn(true);

        WorkflowRuntimeService workflow =
                mock(WorkflowRuntimeService.class);
        ShiftChangeService service =
                shiftService(
                        repository,
                        mock(BusinessNumberService.class),
                        workflow,
                        mock(WorkflowFormService.class));

        ProcessRejectedException error =
                assertThrows(
                        ProcessRejectedException.class,
                        () ->
                                service.act(
                                        ACTOR,
                                        REQUEST,
                                        "VALIDATE_SHIFT",
                                        "p007-conflict",
                                        "hash",
                                        new ShiftChangeService.ActionCommand(
                                                2, null, "validate")));

        assertTrue(
                error.getMessage()
                        .contains("active leave or overtime"));
        verify(repository, never())
                .markValidated(any(), any(), any(), any());
        verify(workflow, never()).get(any(), any());
    }

    @Test
    void p007CanonicalValidationMissCannotAdvanceWorkflow() {
        ShiftChangeService.Repository repository =
                mock(ShiftChangeService.Repository.class);
        when(repository.find(TENANT, REQUEST))
                .thenReturn(
                        Optional.of(
                                shiftRecord(
                                        "S03",
                                        2,
                                        "SCHEDULE")));
        when(repository.isActiveEmployeeInOrg(
                        TENANT, CENTER, EMPLOYEE))
                .thenReturn(true);
        when(repository.hasOverlappingShift(
                        TENANT,
                        EMPLOYEE,
                        START,
                        END,
                        REQUEST))
                .thenReturn(false);
        when(repository.hasAttendanceConflict(
                        TENANT, EMPLOYEE, START, END))
                .thenReturn(false);
        when(repository.markValidated(
                        any(), any(), any(), any()))
                .thenReturn(0);

        WorkflowRuntimeService workflow =
                mock(WorkflowRuntimeService.class);
        ShiftChangeService service =
                shiftService(
                        repository,
                        mock(BusinessNumberService.class),
                        workflow,
                        mock(WorkflowFormService.class));

        ProcessRejectedException error =
                assertThrows(
                        ProcessRejectedException.class,
                        () ->
                                service.act(
                                        ACTOR,
                                        REQUEST,
                                        "VALIDATE_SHIFT",
                                        "p007-mutation-miss",
                                        "hash",
                                        new ShiftChangeService.ActionCommand(
                                                2, null, "validate")));

        assertTrue(error.getMessage().contains("validation fact failed"));
        verify(workflow, never()).get(any(), any());
    }

    @Test
    void p008RejectsZeroDifferenceLedgerEntryBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcLeaveRepository repository =
                new JdbcLeaveRepository(jdbc);

        ProcessRejectedException error =
                assertThrows(
                        ProcessRejectedException.class,
                        () ->
                                repository.appendLedger(
                                        TENANT,
                                        REQUEST,
                                        "ADJUST",
                                        BigDecimal.ZERO,
                                        "no difference",
                                        EMPLOYEE));

        assertTrue(error.getMessage().contains("must be non-zero"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void p010GuardedRepositoryStopsWorkflowWhenCanonicalExamUpdateMisses() {
        JdbcLearningRepository delegate =
                mock(JdbcLearningRepository.class);
        GuardedLearningRepository guarded =
                new GuardedLearningRepository(delegate);
        LearningService.LearningRecord record =
                new LearningService.LearningRecord(
                        REQUEST,
                        TENANT,
                        "P010-HARDEN-001",
                        WORKFLOW,
                        "WFI-P010",
                        "S04",
                        LearningService.label("S04"),
                        3,
                        "安全考试",
                        CENTER,
                        EMPLOYEE,
                        "v1",
                        "COURSE-001",
                        "2026-A",
                        new BigDecimal("100"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now());

        when(delegate.find(TENANT, REQUEST))
                .thenReturn(Optional.of(record));
        when(delegate.evidence(TENANT, REQUEST))
                .thenReturn(List.of());
        when(delegate.updateExam(TENANT, REQUEST, 900, EMPLOYEE))
                .thenReturn(0);

        WorkflowRuntimeService workflow =
                mock(WorkflowRuntimeService.class);
        LearningService service =
                new LearningService(
                        directTransactions(),
                        fixedClaim(),
                        mock(TransactionalOutboxService.class),
                        workflow,
                        mock(WorkflowTaskAssignmentService.class),
                        mock(WorkflowFormService.class),
                        guarded,
                        new ObjectMapper());

        ProcessRejectedException error =
                assertThrows(
                        ProcessRejectedException.class,
                        () ->
                                service.exam(
                                        ACTOR,
                                        REQUEST,
                                        "p010-exam-miss",
                                        "hash",
                                        new LearningService.ExamCommand(
                                                900, "exam evidence")));

        assertTrue(error.getMessage().contains("failed closed"));
        verify(workflow, never()).get(any(), any());
        verify(workflow, never())
                .act(any(WorkflowRuntimeService.ActionCommand.class));
    }

    private static ShiftChangeService shiftService(
            ShiftChangeService.Repository repository,
            BusinessNumberService numbers,
            WorkflowRuntimeService workflow,
            WorkflowFormService forms) {
        return new ShiftChangeService(
                directTransactions(),
                fixedClaim(),
                numbers,
                mock(TransactionalOutboxService.class),
                workflow,
                mock(WorkflowTaskAssignmentService.class),
                forms,
                repository,
                new ObjectMapper());
    }

    private static ShiftChangeService.ShiftRecord shiftRecord(
            String node, int version, String changeAction) {
        return new ShiftChangeService.ShiftRecord(
                REQUEST,
                TENANT,
                "P007-TEST-001",
                WORKFLOW,
                "WFI-P007",
                node,
                ShiftChangeService.label(node),
                version,
                "排班测试",
                null,
                CENTER,
                MANAGER,
                EMPLOYEE,
                null,
                changeAction,
                "测试原因",
                "DAY",
                "2026-08-12",
                START,
                END,
                new BigDecimal("8.000000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());
    }

    private static TenantTransactionRunner directTransactions() {
        TenantTransactionRunner transactions =
                mock(TenantTransactionRunner.class);
        when(transactions.required(
                        any(DatabaseSecurityContext.class),
                        ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(
                        invocation ->
                                ((Supplier<?>)
                                                invocation.getArgument(1))
                                        .get());
        return transactions;
    }

    private static IdempotencyRegistry fixedClaim() {
        IdempotencyRegistry registry =
                mock(IdempotencyRegistry.class);
        when(registry.claim(
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any()))
                .thenReturn(new IdempotencyClaim(REQUEST, false));
        return registry;
    }

    private static WorkflowRuntimeService.Result runtime(
            String node, String process) {
        Instant now = Instant.now();
        WorkflowRuntimeService.Instance instance =
                new WorkflowRuntimeService.Instance(
                        WORKFLOW,
                        TENANT,
                        "WFI-HARDENING",
                        DEFINITION,
                        VERSION,
                        process,
                        "test.aggregate",
                        REQUEST,
                        "TEST-001",
                        "PHASE-10 hardening",
                        EMPLOYEE,
                        node,
                        WorkflowRuntimeService.RUNNING,
                        "NORMAL",
                        now,
                        null,
                        null,
                        new ObjectMapper().createObjectNode());
        return new WorkflowRuntimeService.Result(
                instance, null, null, false);
    }
}
