package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P009 overtime/time-off service. Approval never substitutes for actual labor facts. */
@Service
public final class OvertimeService {
    public static final String PROCESS_CODE="P009";
    public static final String FORM_CODE="CTR-P009-F01";
    public static final String MANAGE_PERMISSION="p009.overtime.manage";
    public static final String REVIEW_PERMISSION="p009.overtime.review";
    public static final String HR_PERMISSION="p009.overtime.hr";
    private static final Duration IDEMPOTENCY_TTL=Duration.ofHours(24);

    private final TenantTransactionRunner tx;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final TransactionalOutboxService outbox;
    private final WorkflowRuntimeService workflow;
    private final WorkflowTaskAssignmentService tasks;
    private final WorkflowFormService forms;
    private final Repository repository;
    private final ObjectMapper mapper;

    public OvertimeService(TenantTransactionRunner tx,IdempotencyRegistry idempotency,BusinessNumberService numbers,
            TransactionalOutboxService outbox,WorkflowRuntimeService workflow,WorkflowTaskAssignmentService tasks,
            WorkflowFormService forms,Repository repository,ObjectMapper mapper){
        this.tx=tx;this.idempotency=idempotency;this.numbers=numbers;this.outbox=outbox;this.workflow=workflow;
        this.tasks=tasks;this.forms=forms;this.repository=repository;this.mapper=mapper;
    }

    public Aggregate create(DatabaseSecurityContext actor,String key,String requestHash,CreateCommand c){
        requireActor(actor);validateCreate(c);
        if(!actor.orgId().equals(c.ownerCenterId()))throw new ProcessRejectedException("P009 owner center must equal authenticated center");
        return tx.required(actor,()->{
            IdempotencyClaim claim=idempotency.claim(actor.tenantId(),actor.employeeId(),key,requestHash,"attendance.overtime_request",UUID.randomUUID(),IDEMPOTENCY_TTL);
            if(claim.existing())return aggregate(actor.tenantId(),claim.resourceId());
            if(repository.hasPlanningConflict(actor.tenantId(),actor.employeeId(),c.startAt(),c.endAt()))
                throw new ProcessRejectedException("P009 overtime window conflicts with leave or another active overtime fact");
            UUID version=repository.workflowVersion(actor.tenantId()).orElseThrow(()->new ProcessRejectedException("P009 workflow is not published"));
            FormRef form=repository.form(actor.tenantId()).orElseThrow(()->new ProcessRejectedException("P009 form is not published"));
            List<UUID> managers=repository.permissionCandidates(actor.tenantId(),MANAGE_PERMISSION,actor.orgId());
            List<UUID> reviewers=repository.permissionCandidates(actor.tenantId(),REVIEW_PERMISSION,actor.orgId());
            List<UUID> hr=repository.permissionCandidates(actor.tenantId(),HR_PERMISSION,actor.orgId());
            if(managers.isEmpty())throw new ProcessRejectedException("P009 manager candidate is missing");
            if(reviewers.isEmpty())throw new ProcessRejectedException("P009 reviewer candidate is missing");
            if(hr.isEmpty())throw new ProcessRejectedException("P009 HR candidate is missing");

            OvertimeRecord record=new OvertimeRecord(
                    claim.resourceId(),actor.tenantId(),numbers.next(actor.tenantId(),actor.employeeId(),PROCESS_CODE),
                    null,null,"S01",label("S01"),0,c.subject().trim(),trim(c.reason()),actor.orgId(),actor.employeeId(),
                    c.attendanceType().trim(),c.startAt(),c.endAt(),hours(c.startAt(),c.endAt()),c.emergencyFact(),
                    null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,Instant.now());
            repository.insert(record,actor.employeeId());
            ObjectNode context=mapper.createObjectNode();
            context.put("ownerEmployeeId",actor.employeeId().toString());context.put("ownerCenterId",actor.orgId().toString());
            context.set("targetEmployeeIds",uuidArray(List.of(actor.employeeId())));context.set("managerCandidateIds",uuidArray(managers));
            context.set("reviewCandidateIds",uuidArray(reviewers));context.set("hrCandidateIds",uuidArray(hr));
            WorkflowRuntimeService.Result started=workflow.start(new WorkflowRuntimeService.StartCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),version,
                    "attendance.overtime_request",record.id(),record.businessNo(),record.subject(),"NORMAL",context,scope(key,"start")));
            forms.submit(new WorkflowFormService.SubmitForm(actor.tenantId(),actor.employeeId(),actor.identityId(),started.instance().id(),null,form.id(),form.versionNo(),List.of(
                    text("subject",record.subject()),text("attendance_type",record.attendanceType()),text("start_at",record.startAt().toString()),
                    text("end_at",record.endAt().toString()),text("emergency_fact",Boolean.toString(record.emergencyFact()))),scope(key,"form")));
            WorkflowRuntimeService.Result moved=workflow.act(new WorkflowRuntimeService.ActionCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),started.instance().id(),null,"S01","SUBMIT_OVERTIME",null,scope(key,"s01")));
            if(repository.bindAndMove(actor.tenantId(),record.id(),0,moved.instance().id(),label("S02"),actor.employeeId())!=1)
                throw new ProcessRejectedException("P009 concurrent create conflict");
            emit(actor,record,"SUBMIT_OVERTIME","S02",recipients(actor.employeeId(),managers,reviewers,hr));
            return aggregate(actor.tenantId(),record.id());
        });
    }

    public Aggregate act(DatabaseSecurityContext actor,UUID id,String actionCode,String key,String requestHash,ActionCommand c){
        requireActor(actor);Objects.requireNonNull(c,"P009 action command is required");String action=safe(actionCode);
        return tx.required(actor,()->{
            Aggregate current=aggregate(actor.tenantId(),id);
            IdempotencyClaim claim=idempotency.claim(actor.tenantId(),actor.employeeId(),key,requestHash,"attendance.overtime_request.action."+action.toLowerCase(Locale.ROOT),id,IDEMPOTENCY_TTL);
            if(claim.existing())return current;
            if(current.record().versionNo()!=c.expectedVersion())throw new ProcessRejectedException("P009 version conflict");
            String node=current.record().currentNodeCode();
            switch(node){
                case "S02"->{require(action,"VALIDATE_NECESSITY");required(repository.markNecessity(actor.tenantId(),id,actor.employeeId()),"P009 necessity validation failed");}
                case "S03"->{if(!"APPROVE_OVERTIME".equals(action)&&!"REJECT_OVERTIME".equals(action))throw new ProcessRejectedException("P009 supervisor action invalid");required(repository.markDecision(actor.tenantId(),id,"APPROVE_OVERTIME".equals(action)?"APPROVED":"REJECTED",actor.employeeId()),"P009 supervisor decision failed");}
                case "S04"->{
                    require(action,"RECORD_ACTUAL_FACT");requireSelf(actor,current.record());BigDecimal actualHours=hours(c.actualStartAt(),c.actualEndAt());
                    if(repository.hasLeaveConflict(actor.tenantId(),current.record().ownerEmployeeId(),c.actualStartAt(),c.actualEndAt()))throw new ProcessRejectedException("P009 actual labor fact overlaps active leave");
                    required(repository.recordActual(actor.tenantId(),id,c.actualStartAt(),c.actualEndAt(),actualHours,requiredText(c.attendanceSummary(),"attendanceSummary"),actor.employeeId()),"P009 actual labor fact failed");
                }
                case "S05"->{require(action,"ACCEPT_RESULT");required(repository.acceptResult(actor.tenantId(),id,requiredText(c.resultSummary(),"resultSummary"),actor.employeeId()),"P009 result acceptance failed");}
                case "S06"->{require(action,"HR_REVIEW");required(repository.markHrReviewed(actor.tenantId(),id,actor.employeeId()),"P009 HR review failed");}
                case "S07"->{
                    require(action,"SET_COMPENSATION_PLAN");String plan=validatePlan(c.compensationPlan(),c.wageAmount(),c.timeOffHours(),c.quotaAccountId());
                    if("TIME_OFF".equals(plan)||"MIXED".equals(plan))repository.appendTimeOffLedger(actor.tenantId(),id,"GRANT",c.timeOffHours(),c.reason(),actor.employeeId());
                    required(repository.setCompensationPlan(actor.tenantId(),id,plan,c.wageAmount(),c.quotaAccountId(),c.timeOffHours(),actor.employeeId()),"P009 compensation plan failed");
                }
                case "S08"->{require(action,"ACK_PAYROLL_RECEIPT");required(repository.ackPayroll(actor.tenantId(),id,requiredText(c.payrollReference(),"payrollReference"),actor.employeeId()),"P009 payroll receipt failed");}
                case "S09"->{require(action,"ARCHIVE");required(repository.archive(actor.tenantId(),id,actor.employeeId()),"P009 archive failed");}
                default->throw new ProcessRejectedException("P009 action is not allowed from current source node");
            }
            current=aggregate(actor.tenantId(),id);
            OvertimeRecord moved=advance(actor,current.record(),node,action,scope(key,"workflow"),c.reason());
            return aggregate(actor.tenantId(),moved.id());
        });
    }

    public Optional<Aggregate> find(DatabaseSecurityContext actor,UUID id){requireActor(actor);return tx.required(actor,()->repository.find(actor.tenantId(),id).map(Aggregate::new));}
    public List<Aggregate> list(DatabaseSecurityContext actor){requireActor(actor);return tx.required(actor,()->repository.list(actor.tenantId()).stream().map(Aggregate::new).toList());}

    private OvertimeRecord advance(DatabaseSecurityContext actor,OvertimeRecord r,String sourceNode,String action,String key,String reason){
        WorkflowRuntimeService.Result runtime=workflow.get(actor.tenantId(),r.workflowInstanceId());
        if(!sourceNode.equals(runtime.instance().currentNodeCode())||!sourceNode.equals(r.currentNodeCode()))throw new ProcessRejectedException("P009 stale workflow projection");
        if(runtime.task()==null)throw new ProcessRejectedException("P009 workflow task missing");
        tasks.claim(new WorkflowTaskAssignmentService.ClaimCommand(actor.tenantId(),runtime.task().id(),actor.employeeId()));
        WorkflowRuntimeService.Result moved=workflow.act(new WorkflowRuntimeService.ActionCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),r.workflowInstanceId(),runtime.task().id(),sourceNode,action,reason,key));
        Instant closed="END".equals(moved.instance().currentNodeCode())?moved.instance().finishedAt():null;
        if(repository.moveStatus(actor.tenantId(),r.id(),r.versionNo(),label(moved.instance().currentNodeCode()),closed,actor.employeeId())!=1)
            throw new ProcessRejectedException("P009 concurrent status transition conflict");
        OvertimeRecord result=repository.find(actor.tenantId(),r.id()).orElseThrow();emit(actor,result,action,moved.instance().currentNodeCode(),List.of(r.ownerEmployeeId()));return result;
    }
    private Aggregate aggregate(UUID tenant,UUID id){return new Aggregate(repository.find(tenant,id).orElseThrow(()->new ProcessRejectedException("P009 overtime request not found")));}
    private void emit(DatabaseSecurityContext actor,OvertimeRecord r,String event,String node,List<UUID> recipients){ObjectNode p=mapper.createObjectNode();p.put("overtimeRequestId",r.id().toString());p.put("businessNo",r.businessNo());p.put("event",event);p.put("nodeCode",node);p.set("recipientEmployeeIds",uuidArray(recipients));outbox.enqueue(new TransactionalOutboxService.Command(actor.tenantId(),actor.employeeId(),"P009_OVERTIME",r.id(),"P009_OVERTIME_EVENT",1,json(p),"p009:"+r.id()+":"+r.versionNo()));}

    private static void validateCreate(CreateCommand c){Objects.requireNonNull(c,"P009 create command is required");req(c.subject(),"subject");req(c.attendanceType(),"attendanceType");if(c.ownerCenterId()==null)throw new ProcessRejectedException("P009 ownerCenterId is required");hours(c.startAt(),c.endAt());}
    private static String validatePlan(String raw,BigDecimal wage,BigDecimal timeOff,String account){String plan=safe(raw);if(!List.of("WAGE","TIME_OFF","MIXED").contains(plan))throw new ProcessRejectedException("P009 compensation plan invalid");if(("WAGE".equals(plan)||"MIXED".equals(plan))&&(wage==null||wage.signum()<0))throw new ProcessRejectedException("P009 wage amount must be non-negative");if(("TIME_OFF".equals(plan)||"MIXED".equals(plan))&&(timeOff==null||timeOff.signum()<=0||account==null||account.isBlank()))throw new ProcessRejectedException("P009 time-off plan requires positive hours and quota account");return plan;}
    private static BigDecimal hours(Instant s,Instant e){if(s==null||e==null||!e.isAfter(s))throw new ProcessRejectedException("P009 end must be after start");return BigDecimal.valueOf(Duration.between(s,e).toMinutes()).divide(BigDecimal.valueOf(60),6,RoundingMode.HALF_UP);}
    private static void requireSelf(DatabaseSecurityContext a,OvertimeRecord r){if(!a.employeeId().equals(r.ownerEmployeeId()))throw new ProcessRejectedException("P009 employee action is self-only");}
    private static void required(int n,String m){if(n!=1)throw new ProcessRejectedException(m);}private static void require(String a,String e){if(!e.equals(a))throw new ProcessRejectedException("P009 action not allowed from current source node");}
    private static void requireActor(DatabaseSecurityContext a){if(a==null||a.tenantId()==null||a.employeeId()==null||a.identityId()==null||a.orgId()==null||a.userId()==null||a.positionId()==null)throw new ProcessRejectedException("P009 authenticated employee context required");}
    private static void req(String v,String f){if(v==null||v.isBlank())throw new ProcessRejectedException("P009 required field missing: "+f);}private static String requiredText(String v,String f){req(v,f);return v.trim();}
    private static String safe(String v){if(v==null)return"";String x=v.trim().toUpperCase(Locale.ROOT);return x.matches("[A-Z0-9_]{1,32}")?x:"";}private static String scope(String k,String s){if(k==null||k.isBlank())throw new ProcessRejectedException("P009 idempotency key required");String x=k+":"+s;if(x.length()>128)throw new ProcessRejectedException("P009 idempotency key too long");return x;}private static String trim(String v){return v==null||v.isBlank()?null:v.trim();}
    private static WorkflowFormService.FieldValue text(String c,String v){return new WorkflowFormService.FieldValue(c,"TEXT",v,null,null,null,null,null,"P1",false);}private ArrayNode uuidArray(List<UUID> ids){ArrayNode a=mapper.createArrayNode();ids.stream().filter(Objects::nonNull).distinct().forEach(x->a.add(x.toString()));return a;}@SafeVarargs private static List<UUID> recipients(UUID employee,List<UUID>... groups){List<UUID> v=new ArrayList<>();v.add(employee);for(List<UUID> g:groups)v.addAll(g);return v.stream().filter(Objects::nonNull).distinct().toList();}private String json(JsonNode n){try{return mapper.writeValueAsString(n);}catch(JsonProcessingException e){throw new ProcessRejectedException("P009 JSON serialization failed",e);}}
    public static String label(String n){return switch(n){case"S01"->"事前申请/紧急事实登记";case"S02"->"必要性与任务校验";case"S03"->"主管审批";case"S04"->"实际考勤与劳动事实";case"S05"->"成果验收";case"S06"->"人事复核";case"S07"->"法定工资/调休方案";case"S08"->"薪酬回执";case"S09"->"归档";case"END"->"已关闭";default->throw new ProcessRejectedException("P009 unknown workflow node: "+n);};}

    public interface Repository{
        Optional<UUID> workflowVersion(UUID t);Optional<FormRef> form(UUID t);List<UUID> permissionCandidates(UUID t,String p,UUID org);
        boolean hasPlanningConflict(UUID t,UUID employee,Instant s,Instant e);boolean hasLeaveConflict(UUID t,UUID employee,Instant s,Instant e);
        void insert(OvertimeRecord r,UUID actor);int bindAndMove(UUID t,UUID id,int v,UUID wf,String status,UUID actor);int moveStatus(UUID t,UUID id,int v,String status,Instant closed,UUID actor);
        int markNecessity(UUID t,UUID id,UUID actor);int markDecision(UUID t,UUID id,String decision,UUID actor);int recordActual(UUID t,UUID id,Instant s,Instant e,BigDecimal hours,String summary,UUID actor);int acceptResult(UUID t,UUID id,String result,UUID actor);int markHrReviewed(UUID t,UUID id,UUID actor);int setCompensationPlan(UUID t,UUID id,String plan,BigDecimal wage,String account,BigDecimal timeOff,UUID actor);int ackPayroll(UUID t,UUID id,String reference,UUID actor);int archive(UUID t,UUID id,UUID actor);void appendTimeOffLedger(UUID t,UUID id,String type,BigDecimal amount,String note,UUID actor);Optional<OvertimeRecord> find(UUID t,UUID id);List<OvertimeRecord> list(UUID t);
    }
    public record FormRef(UUID id,int versionNo){}
    public record CreateCommand(String subject,String reason,UUID ownerCenterId,String attendanceType,Instant startAt,Instant endAt,boolean emergencyFact){}
    public record ActionCommand(int expectedVersion,String reason,Instant actualStartAt,Instant actualEndAt,String attendanceSummary,String resultSummary,String compensationPlan,BigDecimal wageAmount,String quotaAccountId,BigDecimal timeOffHours,String payrollReference){}
    public record OvertimeRecord(UUID id,UUID tenantId,String businessNo,UUID workflowInstanceId,String workflowInstanceNo,String currentNodeCode,String status,int versionNo,String subject,String reason,UUID ownerCenterId,UUID ownerEmployeeId,String attendanceType,Instant startAt,Instant endAt,BigDecimal durationHours,boolean emergencyFact,Instant necessityCheckedAt,String supervisorDecision,Instant supervisorApprovedAt,Instant supervisorRejectedAt,Instant actualStartAt,Instant actualEndAt,BigDecimal actualDurationHours,String actualAttendanceSummary,Instant actualFactRecordedAt,String resultSummary,Instant resultAcceptedAt,Instant hrReviewedAt,String compensationPlan,BigDecimal actualAmount,String quotaAccountId,BigDecimal quotaAmount,Instant compensationPlannedAt,String payrollReference,Instant payrollReceiptAt,Instant archivedAt,Instant closedAt,Instant updatedAt){}
    public record Aggregate(OvertimeRecord record){public Aggregate metadataOnly(){OvertimeRecord r=record;return new Aggregate(new OvertimeRecord(r.id(),r.tenantId(),r.businessNo(),r.workflowInstanceId(),r.workflowInstanceNo(),r.currentNodeCode(),r.status(),r.versionNo(),null,null,r.ownerCenterId(),null,r.attendanceType(),r.startAt(),r.endAt(),r.durationHours(),r.emergencyFact(),r.necessityCheckedAt(),r.supervisorDecision(),r.supervisorApprovedAt(),r.supervisorRejectedAt(),r.actualStartAt(),r.actualEndAt(),r.actualDurationHours(),null,r.actualFactRecordedAt(),null,r.resultAcceptedAt(),r.hrReviewedAt(),r.compensationPlan(),null,null,null,r.compensationPlannedAt(),null,r.payrollReceiptAt(),r.archivedAt(),r.closedAt(),r.updatedAt()));}}
}
