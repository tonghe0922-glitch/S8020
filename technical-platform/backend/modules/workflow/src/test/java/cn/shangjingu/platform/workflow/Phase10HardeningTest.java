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
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/** PHASE-10 regression tests for employee self-service and fail-closed adapters. */
class Phase10HardeningTest {
    private static final UUID TENANT=UUID.fromString("11000000-0000-0000-0000-000000000010");
    private static final UUID USER=UUID.fromString("21000000-0000-0000-0000-000000000010");
    private static final UUID IDENTITY=UUID.fromString("31000000-0000-0000-0000-000000000010");
    private static final UUID EMPLOYEE=UUID.fromString("41000000-0000-0000-0000-000000000010");
    private static final UUID OTHER=UUID.fromString("42000000-0000-0000-0000-000000000010");
    private static final UUID CENTER=UUID.fromString("51000000-0000-0000-0000-000000000010");
    private static final UUID POSITION=UUID.fromString("61000000-0000-0000-0000-000000000010");
    private static final UUID REQUEST=UUID.fromString("71000000-0000-0000-0000-000000000010");
    private static final UUID WORKFLOW=UUID.fromString("81000000-0000-0000-0000-000000000010");
    private static final UUID TASK=UUID.fromString("91000000-0000-0000-0000-000000000010");
    private static final UUID DEFINITION=UUID.fromString("a1000000-0000-0000-0000-000000000010");
    private static final UUID VERSION=UUID.fromString("b1000000-0000-0000-0000-000000000010");
    private static final UUID FORM=UUID.fromString("c1000000-0000-0000-0000-000000000010");
    private static final Instant START=Instant.parse("2026-08-13T01:00:00Z");
    private static final Instant END=Instant.parse("2026-08-13T09:00:00Z");
    private static final DatabaseSecurityContext ACTOR=
            new DatabaseSecurityContext(TENANT,USER,IDENTITY,EMPLOYEE,null,CENTER,POSITION);

    @Test
    void employeeCanCreateOwnShiftChangeWithoutScheduleManagerRole(){
        ShiftChangeService.Repository repo=mock(ShiftChangeService.Repository.class);
        BusinessNumberService numbers=mock(BusinessNumberService.class);
        WorkflowRuntimeService workflow=mock(WorkflowRuntimeService.class);
        when(repo.isActiveEmployeeInOrg(TENANT,CENTER,EMPLOYEE)).thenReturn(true);
        when(repo.workflowVersion(TENANT)).thenReturn(Optional.of(VERSION));
        when(repo.form(TENANT)).thenReturn(Optional.of(new ShiftChangeService.FormRef(FORM,1)));
        when(repo.permissionCandidates(TENANT,ShiftChangeService.MANAGE_PERMISSION,CENTER)).thenReturn(List.of(OTHER));
        when(numbers.next(TENANT,EMPLOYEE,ShiftChangeService.PROCESS_CODE)).thenReturn("P007-SELF-001");
        when(workflow.start(any())).thenReturn(runtime("S01"));
        when(workflow.act(any())).thenReturn(runtime("S02"));
        when(repo.bindAndMove(TENANT,REQUEST,0,WORKFLOW,ShiftChangeService.label("S02"),EMPLOYEE)).thenReturn(1);
        when(repo.find(TENANT,REQUEST)).thenReturn(Optional.of(shift("S02",1,"SHIFT_CHANGE")));
        ShiftChangeService service=service(repo,numbers,workflow);
        ShiftChangeService.Aggregate result=service.create(ACTOR,"p007-self","hash",create("SHIFT_CHANGE"));
        assertEquals("S02",result.record().currentNodeCode());
        assertEquals("SHIFT_CHANGE",result.record().changeAction());
        verify(repo).insert(any(),any());
    }

    @Test
    void employeeCannotForgeScheduleCreation(){
        ShiftChangeService.Repository repo=mock(ShiftChangeService.Repository.class);
        when(repo.isActiveEmployeeInOrg(TENANT,CENTER,EMPLOYEE)).thenReturn(true);
        when(repo.workflowVersion(TENANT)).thenReturn(Optional.of(VERSION));
        when(repo.form(TENANT)).thenReturn(Optional.of(new ShiftChangeService.FormRef(FORM,1)));
        when(repo.permissionCandidates(TENANT,ShiftChangeService.MANAGE_PERMISSION,CENTER)).thenReturn(List.of(OTHER));
        WorkflowRuntimeService workflow=mock(WorkflowRuntimeService.class);
        ProcessRejectedException error=assertThrows(ProcessRejectedException.class,
                ()->service(repo,mock(BusinessNumberService.class),workflow)
                        .create(ACTOR,"p007-forged","hash",create("SCHEDULE")));
        assertTrue(error.getMessage().contains("manager"));
        verify(repo,never()).insert(any(),any());
        verify(workflow,never()).start(any());
    }

    @Test
    void crossProcessConflictStopsP007BeforeWorkflowMutation(){
        ShiftChangeService.Repository repo=mock(ShiftChangeService.Repository.class);
        when(repo.find(TENANT,REQUEST)).thenReturn(Optional.of(shift("S03",2,"SCHEDULE")));
        when(repo.isActiveEmployeeInOrg(TENANT,CENTER,EMPLOYEE)).thenReturn(true);
        when(repo.hasOverlappingShift(TENANT,EMPLOYEE,START,END,REQUEST)).thenReturn(false);
        when(repo.hasAttendanceConflict(TENANT,EMPLOYEE,START,END)).thenReturn(true);
        WorkflowRuntimeService workflow=mock(WorkflowRuntimeService.class);
        ProcessRejectedException error=assertThrows(ProcessRejectedException.class,
                ()->service(repo,mock(BusinessNumberService.class),workflow).act(ACTOR,REQUEST,
                        "VALIDATE_SHIFT","p007-conflict","hash",
                        new ShiftChangeService.ActionCommand(2,null,"validate")));
        assertTrue(error.getMessage().contains("leave or overtime"));
        verify(repo,never()).markValidated(any(),any(),any(),any());
        verify(workflow,never()).get(any(),any());
    }

    @Test
    void guardedRepositoriesRejectMissingCanonicalMutations(){
        JdbcShiftChangeRepository shiftDelegate=mock(JdbcShiftChangeRepository.class);
        when(shiftDelegate.markValidated(TENANT,REQUEST,BigDecimal.ONE,EMPLOYEE)).thenReturn(0);
        assertThrows(ProcessRejectedException.class,()->new GuardedShiftChangeRepository(
                shiftDelegate,mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .markValidated(TENANT,REQUEST,BigDecimal.ONE,EMPLOYEE));
        JdbcLearningRepository learningDelegate=mock(JdbcLearningRepository.class);
        when(learningDelegate.updateExam(TENANT,REQUEST,900L,EMPLOYEE)).thenReturn(0);
        assertThrows(ProcessRejectedException.class,()->new GuardedLearningRepository(learningDelegate)
                .updateExam(TENANT,REQUEST,900L,EMPLOYEE));
    }

    @Test
    void p008RejectsZeroAdjustmentAndReturnBeforeLeaveStart(){
        JdbcLeaveRepository delegate=mock(JdbcLeaveRepository.class);
        GuardedLeaveRepository guarded=new GuardedLeaveRepository(delegate);
        assertThrows(ProcessRejectedException.class,()->guarded.appendLedger(
                TENANT,REQUEST,"ADJUST",BigDecimal.ZERO,"zero",EMPLOYEE));
        verify(delegate,never()).appendLedger(any(),any(),anyString(),any(),any(),any());
        when(delegate.find(TENANT,REQUEST)).thenReturn(Optional.of(leave(START)));
        ProcessRejectedException error=assertThrows(ProcessRejectedException.class,
                ()->guarded.markReturned(TENANT,REQUEST,START.minusSeconds(1),EMPLOYEE));
        assertTrue(error.getMessage().contains("cannot precede"));
        verify(delegate,never()).markReturned(any(),any(),any(),any());
    }

    private static ShiftChangeService service(ShiftChangeService.Repository repo,
            BusinessNumberService numbers,WorkflowRuntimeService workflow){
        return new ShiftChangeService(directTransactions(),fixedClaim(),numbers,
                mock(TransactionalOutboxService.class),workflow,
                mock(WorkflowTaskAssignmentService.class),mock(WorkflowFormService.class),
                repo,new ObjectMapper());
    }
    private static ShiftChangeService.CreateCommand create(String action){return new ShiftChangeService.CreateCommand("员工换班",null,CENTER,EMPLOYEE,action,"家庭安排","MORNING","2026-08-13",START,END);}
    private static ShiftChangeService.ShiftRecord shift(String node,int version,String action){return new ShiftChangeService.ShiftRecord(REQUEST,TENANT,"P007-TEST","S01".equals(node)?null:WORKFLOW,"S01".equals(node)?null:"WFI-P007",node,ShiftChangeService.label(node),version,"员工换班",null,CENTER,EMPLOYEE,EMPLOYEE,null,action,"家庭安排","MORNING","2026-08-13",START,END,new BigDecimal("8.000000"),null,null,null,null,null,null,null,null,null,null,Instant.now());}
    private static LeaveService.LeaveRecord leave(Instant leaveStartedAt){return new LeaveService.LeaveRecord(REQUEST,TENANT,"P008-TEST",WORKFLOW,"WFI-P008","S08",LeaveService.label("S08"),7,"年休假",null,CENTER,EMPLOYEE,"ANNUAL_LEAVE",START,END,new BigDecimal("8.000000"),"ANNUAL-2026",BigDecimal.ONE,EMPLOYEE.toString(),"handover",Instant.now(),Instant.now(),"APPROVED",Instant.now(),null,Instant.now(),Instant.now(),leaveStartedAt,null,null,null,null,Instant.now());}
    private static TenantTransactionRunner directTransactions(){TenantTransactionRunner tx=mock(TenantTransactionRunner.class);when(tx.required(any(DatabaseSecurityContext.class),ArgumentMatchers.<Supplier<Object>>any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(1)).get());return tx;}
    private static IdempotencyRegistry fixedClaim(){IdempotencyRegistry registry=mock(IdempotencyRegistry.class);when(registry.claim(any(),any(),anyString(),anyString(),anyString(),any(),any())).thenReturn(new IdempotencyClaim(REQUEST,false));return registry;}
    private static WorkflowRuntimeService.Result runtime(String node){Instant now=Instant.now();WorkflowRuntimeService.Instance instance=new WorkflowRuntimeService.Instance(WORKFLOW,TENANT,"WFI-P007",DEFINITION,VERSION,"P007","attendance.shift_change_request",REQUEST,"P007-TEST","employee shift change",EMPLOYEE,node,WorkflowRuntimeService.RUNNING,"NORMAL",now,null,null,new ObjectMapper().createObjectNode());WorkflowRuntimeService.Task task=new WorkflowRuntimeService.Task(TASK,TENANT,WORKFLOW,"WFT-P007",node,"TASK",EMPLOYEE,null,WorkflowRuntimeService.PENDING,now,null,null,null,null);return new WorkflowRuntimeService.Result(instance,task,null,false);}
}
