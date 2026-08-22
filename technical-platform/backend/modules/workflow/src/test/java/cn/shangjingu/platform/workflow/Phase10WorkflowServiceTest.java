package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * PHASE-10 executable service gate for P006-P010.
 *
 * <p>The tests deliberately call the production application services. P008 exercises every
 * approved transition from quota reservation to day-close, while the remaining tests protect
 * fail-closed state guards that previously had no executable coverage.</p>
 */
class Phase10WorkflowServiceTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000010");
    private static final UUID USER = UUID.fromString("20000000-0000-0000-0000-000000000010");
    private static final UUID IDENTITY = UUID.fromString("30000000-0000-0000-0000-000000000010");
    private static final UUID EMPLOYEE = UUID.fromString("40000000-0000-0000-0000-000000000010");
    private static final UUID CENTER = UUID.fromString("50000000-0000-0000-0000-000000000010");
    private static final UUID POSITION = UUID.fromString("60000000-0000-0000-0000-000000000010");
    private static final UUID REQUEST = UUID.fromString("70000000-0000-0000-0000-000000000010");
    private static final UUID WORKFLOW = UUID.fromString("80000000-0000-0000-0000-000000000010");
    private static final UUID TASK = UUID.fromString("90000000-0000-0000-0000-000000000010");
    private static final UUID DEFINITION = UUID.fromString("a0000000-0000-0000-0000-000000000010");
    private static final UUID VERSION = UUID.fromString("b0000000-0000-0000-0000-000000000010");
    private static final DatabaseSecurityContext ACTOR =
            new DatabaseSecurityContext(TENANT, USER, IDENTITY, EMPLOYEE, null, CENTER, POSITION);

    @Test
    void p008ApprovedLeaveCompletesAllNineTransitionsAndAppendsLedgerOnly() {
        ObjectMapper mapper = new ObjectMapper();
        TenantTransactionRunner tx = directTransactions();
        IdempotencyRegistry idempotency = freshClaims();
        BusinessNumberService numbers = mock(BusinessNumberService.class);
        TransactionalOutboxService outbox = mock(TransactionalOutboxService.class);
        WorkflowFormService forms = mock(WorkflowFormService.class);
        WorkflowTaskAssignmentService tasks = claimedTasks();
        WorkflowRuntimeService workflow = mock(WorkflowRuntimeService.class);
        LeaveService.Repository repository = mock(LeaveService.Repository.class);
        LeaveState state = new LeaveState();
        List<LeaveService.LedgerEntry> ledger = new ArrayList<>();
        AtomicReference<String> runtimeNode = new AtomicReference<>("S02");

        when(repository.find(TENANT, REQUEST)).thenAnswer(invocation -> Optional.of(state.snapshot()));
        when(repository.list(TENANT)).thenAnswer(invocation -> List.of(state.snapshot()));
        when(repository.ledger(TENANT)).thenAnswer(invocation -> List.copyOf(ledger));
        when(repository.markQuotaReserved(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.quotaReservedAt = Instant.now();
            return 1;
        });
        when(repository.markHandover(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.handoverConfirmedAt = Instant.now();
            return 1;
        });
        when(repository.markDecision(TENANT, REQUEST, "APPROVED", EMPLOYEE)).thenAnswer(invocation -> {
            state.decision = "APPROVED";
            state.approvedAt = Instant.now();
            return 1;
        });
        when(repository.markQuotaSettled(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.quotaSettledAt = Instant.now();
            return 1;
        });
        when(repository.markAttendance(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.attendanceMarkedAt = Instant.now();
            return 1;
        });
        when(repository.markLeaveStarted(any(), any(), any(), any())).thenAnswer(invocation -> {
            state.leaveStartedAt = invocation.getArgument(2);
            return 1;
        });
        when(repository.markReturned(any(), any(), any(), any())).thenAnswer(invocation -> {
            state.returnedAt = invocation.getArgument(2);
            return 1;
        });
        when(repository.markQuotaAdjusted(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.quotaAdjustedAt = Instant.now();
            return 1;
        });
        when(repository.markDayClosed(TENANT, REQUEST, EMPLOYEE)).thenAnswer(invocation -> {
            state.dayClosedAt = Instant.now();
            return 1;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
                    String type = invocation.getArgument(2);
                    BigDecimal amount = invocation.getArgument(3);
                    String note = invocation.getArgument(4);
                    ledger.add(new LeaveService.LedgerEntry(
                            UUID.randomUUID(),
                            REQUEST,
                            "P008-TEST",
                            CENTER,
                            EMPLOYEE,
                            ledger.size() + 1,
                            type,
                            amount,
                            note,
                            Instant.now()));
                    return null;
                })
                .when(repository)
                .appendLedger(any(), any(), anyString(), any(), any(), any());
        when(repository.moveStatus(any(), any(), anyInt(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    state.node = runtimeNode.get();
                    state.status = invocation.getArgument(3);
                    state.closedAt = invocation.getArgument(4);
                    state.version++;
                    state.updatedAt = Instant.now();
                    return 1;
                });
        when(workflow.get(TENANT, WORKFLOW)).thenAnswer(invocation -> runtime(runtimeNode.get(), "P008"));
        when(workflow.act(any(WorkflowRuntimeService.ActionCommand.class))).thenAnswer(invocation -> {
            WorkflowRuntimeService.ActionCommand command = invocation.getArgument(0);
            String next =
                    switch (command.actionCode()) {
                        case "RESERVE_QUOTA" -> "S03";
                        case "CONFIRM_HANDOVER" -> "S04";
                        case "APPROVE_LEAVE" -> "S05";
                        case "COMMIT_QUOTA" -> "S06";
                        case "MARK_ATTENDANCE" -> "S07";
                        case "START_LEAVE" -> "S08";
                        case "RETURN_TO_WORK" -> "S09";
                        case "ADJUST_QUOTA" -> "S10";
                        case "CLOSE_DAY" -> "END";
                        default -> throw new AssertionError(command.actionCode());
                    };
            runtimeNode.set(next);
            return runtime(next, "P008");
        });

        LeaveService service =
                new LeaveService(tx, idempotency, numbers, outbox, workflow, tasks, forms, repository, mapper);
        runLeaveAction(service, state, "RESERVE_QUOTA", null, null);
        runLeaveAction(service, state, "CONFIRM_HANDOVER", null, null);
        runLeaveAction(service, state, "APPROVE_LEAVE", null, null);
        runLeaveAction(service, state, "COMMIT_QUOTA", null, null);
        runLeaveAction(service, state, "MARK_ATTENDANCE", null, null);
        runLeaveAction(service, state, "START_LEAVE", Instant.parse("2026-08-12T01:00:00Z"), null);
        runLeaveAction(service, state, "RETURN_TO_WORK", Instant.parse("2026-08-12T09:00:00Z"), null);
        runLeaveAction(service, state, "ADJUST_QUOTA", null, new BigDecimal("-0.500000"));
        LeaveService.Aggregate closed = runLeaveAction(service, state, "CLOSE_DAY", null, null);

        assertEquals("END", closed.record().currentNodeCode());
        assertEquals(9, closed.record().versionNo());
        assertNotNull(closed.record().quotaReservedAt());
        assertNotNull(closed.record().handoverConfirmedAt());
        assertNotNull(closed.record().quotaSettledAt());
        assertNotNull(closed.record().attendanceMarkedAt());
        assertNotNull(closed.record().leaveStartedAt());
        assertNotNull(closed.record().returnedAt());
        assertNotNull(closed.record().quotaAdjustedAt());
        assertNotNull(closed.record().dayClosedAt());
        assertEquals(
                List.of("RESERVE", "DEDUCT", "ADJUST"),
                ledger.stream().map(LeaveService.LedgerEntry::entryType).toList());
        assertEquals(
                List.of(new BigDecimal("1.000000"), new BigDecimal("1.000000"), new BigDecimal("-0.500000")),
                ledger.stream().map(LeaveService.LedgerEntry::amount).toList());
        verify(tasks, org.mockito.Mockito.times(9)).claim(any());
        verify(workflow, org.mockito.Mockito.times(9)).act(any(WorkflowRuntimeService.ActionCommand.class));
    }

    @Test
    void p010PermissionLinkIsFailClosedWhenNoBindingExists() {
        LearningService.Repository repository = mock(LearningService.Repository.class);
        LearningService.LearningRecord record = new LearningService.LearningRecord(
                REQUEST,
                TENANT,
                "P010-TEST",
                WORKFLOW,
                "WFI-P010",
                "S08",
                LearningService.label("S08"),
                7,
                "高风险岗位资格",
                CENTER,
                EMPLOYEE,
                "v1",
                "COURSE-001",
                "2026-A",
                new BigDecimal("100"),
                Long.valueOf(900),
                "通过",
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2027, 8, 12),
                Instant.now(),
                EMPLOYEE,
                null,
                null,
                Instant.now());
        when(repository.find(TENANT, REQUEST)).thenReturn(Optional.of(record));
        when(repository.evidence(TENANT, REQUEST)).thenReturn(List.of());
        when(repository.linkPermissions(TENANT, REQUEST, EMPLOYEE)).thenReturn(List.of());
        WorkflowRuntimeService workflow = mock(WorkflowRuntimeService.class);
        LearningService service = new LearningService(
                directTransactions(),
                freshClaims(),
                mock(TransactionalOutboxService.class),
                workflow,
                mock(WorkflowTaskAssignmentService.class),
                mock(WorkflowFormService.class),
                repository,
                new ObjectMapper());

        ProcessRejectedException error = assertThrows(
                ProcessRejectedException.class,
                () -> service.action(
                        ACTOR,
                        REQUEST,
                        "LINK_PERMISSIONS",
                        "p010-link",
                        "hash",
                        new LearningService.ActionCommand(
                                7, "binding required", LocalDate.of(2026, 8, 12), LocalDate.of(2027, 8, 12))));

        assertTrue(error.getMessage().contains("fail closed"));
        verify(repository, never()).markPermissionLinked(any(), any(), any());
        verify(repository, never()).appendEvidence(any(), any(), anyString(), any(), any(), any(), any(), any(), any());
        verify(workflow, never()).get(any(), any());
        verify(workflow, never()).act(any());
    }

    @Test
    void p006MinutesCannotAdvanceWithoutConfirmedMinutesText() {
        MeetingService.Repository repository = mock(MeetingService.Repository.class);
        MeetingService.Meeting meeting = new MeetingService.Meeting(
                REQUEST,
                TENANT,
                "P006-TEST",
                WORKFLOW,
                "WFI-P006",
                "S06",
                MeetingService.label("S06"),
                5,
                "安全例会",
                "例会",
                "内容",
                "签到",
                "P006_MEETING",
                EMPLOYEE.toString(),
                "内部",
                "会议室",
                CENTER,
                EMPLOYEE,
                LocalDate.of(2026, 8, 12),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                Instant.now());
        when(repository.findMeeting(TENANT, REQUEST)).thenReturn(Optional.of(meeting));
        when(repository.listItems(TENANT, REQUEST)).thenReturn(List.of());
        MeetingService service = new MeetingService(
                directTransactions(),
                freshClaims(),
                mock(BusinessNumberService.class),
                mock(TransactionalOutboxService.class),
                mock(WorkflowRuntimeService.class),
                mock(WorkflowTaskAssignmentService.class),
                mock(WorkflowFormService.class),
                repository,
                new ObjectMapper());

        ProcessRejectedException error = assertThrows(
                ProcessRejectedException.class,
                () -> service.act(
                        ACTOR,
                        REQUEST,
                        "CONFIRM_MINUTES",
                        "p006-minutes",
                        "hash",
                        new MeetingService.ActionCommand(5, null, List.of(), Map.of(), List.of(), null)));

        assertTrue(error.getMessage().contains("minutesText"));
        verify(repository, never()).confirmMinutes(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void p009TimeOffPlanRejectsMissingQuotaAccountBeforeAnyLedgerWrite() {
        OvertimeService.Repository repository = mock(OvertimeService.Repository.class);
        OvertimeService.OvertimeRecord record = new OvertimeService.OvertimeRecord(
                REQUEST,
                TENANT,
                "P009-TEST",
                WORKFLOW,
                "WFI-P009",
                "S07",
                OvertimeService.label("S07"),
                6,
                "闭园加班",
                null,
                CENTER,
                EMPLOYEE,
                "OVERTIME",
                Instant.now().minusSeconds(7200),
                Instant.now(),
                new BigDecimal("2.000000"),
                false,
                Instant.now(),
                "APPROVED",
                Instant.now(),
                null,
                Instant.now().minusSeconds(7200),
                Instant.now(),
                new BigDecimal("2.000000"),
                "门禁事实",
                Instant.now(),
                "成果完成",
                Instant.now(),
                Instant.now(),
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
        when(repository.find(TENANT, REQUEST)).thenReturn(Optional.of(record));
        OvertimeService service = new OvertimeService(
                directTransactions(),
                freshClaims(),
                mock(BusinessNumberService.class),
                mock(TransactionalOutboxService.class),
                mock(WorkflowRuntimeService.class),
                mock(WorkflowTaskAssignmentService.class),
                mock(WorkflowFormService.class),
                repository,
                new ObjectMapper());

        ProcessRejectedException error = assertThrows(
                ProcessRejectedException.class,
                () -> service.act(
                        ACTOR,
                        REQUEST,
                        "SET_COMPENSATION_PLAN",
                        "p009-plan",
                        "hash",
                        new OvertimeService.ActionCommand(
                                6,
                                "plan",
                                null,
                                null,
                                null,
                                null,
                                "TIME_OFF",
                                BigDecimal.ZERO,
                                null,
                                new BigDecimal("2"),
                                null)));

        assertTrue(error.getMessage().contains("quota account"));
        verify(repository, never()).appendTimeOffLedger(any(), any(), anyString(), any(), any(), any());
        verify(repository, never()).setCompensationPlan(any(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void p007LabelsCoverEverySourceBackedNodeAndRejectUnknownNodes() {
        assertEquals(
                List.of(
                        "业务量与活动需求输入",
                        "班次模板匹配",
                        "资格与连续工时校验",
                        "主管发布排班",
                        "员工确认",
                        "换班替班申请",
                        "变更审批",
                        "考勤与餐饮班车联动",
                        "日结",
                        "已关闭"),
                List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "END").stream()
                        .map(ShiftChangeService::label)
                        .toList());
        assertThrows(ProcessRejectedException.class, () -> ShiftChangeService.label("S10"));
    }

    private static LeaveService.Aggregate runLeaveAction(
            LeaveService service, LeaveState state, String action, Instant actual, BigDecimal adjustment) {
        return service.act(
                ACTOR,
                REQUEST,
                action,
                "p008-" + action.toLowerCase(),
                "hash-" + action,
                new LeaveService.ActionCommand(state.version, "verified", actual, adjustment));
    }

    private static TenantTransactionRunner directTransactions() {
        TenantTransactionRunner transactions = mock(TenantTransactionRunner.class);
        when(transactions.required(any(DatabaseSecurityContext.class), ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return transactions;
    }

    private static IdempotencyRegistry freshClaims() {
        IdempotencyRegistry registry = mock(IdempotencyRegistry.class);
        when(registry.claim(any(), any(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new IdempotencyClaim(invocation.getArgument(5), false));
        return registry;
    }

    private static WorkflowTaskAssignmentService claimedTasks() {
        WorkflowTaskAssignmentService tasks = mock(WorkflowTaskAssignmentService.class);
        when(tasks.claim(any())).thenAnswer(invocation -> {
            WorkflowTaskAssignmentService.ClaimCommand command = invocation.getArgument(0);
            return new WorkflowTaskAssignmentService.ClaimResult(
                    command.taskId(), WORKFLOW, command.claimantId(), List.of(command.claimantId()), false);
        });
        return tasks;
    }

    private static WorkflowRuntimeService.Result runtime(String node, String process) {
        boolean terminal = "END".equals(node);
        Instant now = Instant.now();
        WorkflowRuntimeService.Instance instance = new WorkflowRuntimeService.Instance(
                WORKFLOW,
                TENANT,
                "WFI-TEST",
                DEFINITION,
                VERSION,
                process,
                "test.aggregate",
                REQUEST,
                "TEST-001",
                "Phase10 executable gate",
                EMPLOYEE,
                node,
                terminal ? WorkflowRuntimeService.COMPLETED : WorkflowRuntimeService.RUNNING,
                "NORMAL",
                now,
                terminal ? now : null,
                null,
                new ObjectMapper().createObjectNode());
        WorkflowRuntimeService.Task task = terminal
                ? null
                : new WorkflowRuntimeService.Task(
                        TASK,
                        TENANT,
                        WORKFLOW,
                        "WFT-TEST",
                        node,
                        "TASK",
                        EMPLOYEE,
                        null,
                        WorkflowRuntimeService.PENDING,
                        now,
                        null,
                        null,
                        null,
                        null);
        return new WorkflowRuntimeService.Result(instance, task, null, false);
    }

    private static final class LeaveState {
        int version;
        String node = "S02";
        String status = LeaveService.label("S02");
        String decision;
        Instant quotaReservedAt;
        Instant handoverConfirmedAt;
        Instant approvedAt;
        Instant quotaSettledAt;
        Instant attendanceMarkedAt;
        Instant leaveStartedAt;
        Instant returnedAt;
        Instant quotaAdjustedAt;
        Instant dayClosedAt;
        Instant closedAt;
        Instant updatedAt = Instant.now();

        LeaveService.LeaveRecord snapshot() {
            return new LeaveService.LeaveRecord(
                    REQUEST,
                    TENANT,
                    "P008-TEST",
                    WORKFLOW,
                    "WFI-P008",
                    node,
                    status,
                    version,
                    "年休假",
                    null,
                    CENTER,
                    EMPLOYEE,
                    "ANNUAL_LEAVE",
                    Instant.parse("2026-08-12T01:00:00Z"),
                    Instant.parse("2026-08-12T09:00:00Z"),
                    new BigDecimal("8.000000"),
                    "ANNUAL-2026",
                    new BigDecimal("1.000000"),
                    EMPLOYEE.toString(),
                    "已完成工作交接",
                    quotaReservedAt,
                    handoverConfirmedAt,
                    decision,
                    approvedAt,
                    null,
                    quotaSettledAt,
                    attendanceMarkedAt,
                    leaveStartedAt,
                    returnedAt,
                    quotaAdjustedAt,
                    dayClosedAt,
                    closedAt,
                    updatedAt);
        }
    }
}
