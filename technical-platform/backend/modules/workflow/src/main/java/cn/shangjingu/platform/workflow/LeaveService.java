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

/** P008 leave, quota-ledger, handover and attendance application service. */
@Service
public final class LeaveService {
    public static final String PROCESS_CODE="P008";
    public static final String FORM_CODE="CTR-P008-F01";
    public static final String MANAGE_PERMISSION="p008.leave.manage";
    public static final String REVIEW_PERMISSION="p008.leave.review";
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

    public LeaveService(TenantTransactionRunner tx,IdempotencyRegistry idempotency,BusinessNumberService numbers,
            TransactionalOutboxService outbox,WorkflowRuntimeService workflow,WorkflowTaskAssignmentService tasks,
            WorkflowFormService forms,Repository repository,ObjectMapper mapper){
        this.tx=tx;this.idempotency=idempotency;this.numbers=numbers;this.outbox=outbox;this.workflow=workflow;this.tasks=tasks;
        this.forms=forms;this.repository=repository;this.mapper=mapper;
    }

    public Aggregate create(DatabaseSecurityContext actor,String key,String requestHash,CreateCommand command){
        requireActor(actor);validateCreate(command);
        if(!actor.orgId().equals(command.ownerCenterId()))throw new ProcessRejectedException("P008 owner center must equal authenticated center");
        return tx.required(actor,()->{
            IdempotencyClaim claim=idempotency.claim(actor.tenantId(),actor.employeeId(),key,requestHash,"attendance.leave_request",UUID.randomUUID(),IDEMPOTENCY_TTL);
            if(claim.existing())return aggregate(actor.tenantId(),claim.resourceId());
            if(repository.hasTimeConflict(actor.tenantId(),actor.employeeId(),command.startAt(),command.endAt()))
                throw new ProcessRejectedException("P008 leave window conflicts with active leave, schedule or overtime fact");
            UUID version=repository.workflowVersion(actor.tenantId()).orElseThrow(()->new ProcessRejectedException("P008 workflow is not published"));
            FormRef form=repository.form(actor.tenantId()).orElseThrow(()->new ProcessRejectedException("P008 form is not published"));
            List<UUID> managers=repository.permissionCandidates(actor.tenantId(),MANAGE_PERMISSION,actor.orgId());
            List<UUID> reviewers=repository.permissionCandidates(actor.tenantId(),REVIEW_PERMISSION,actor.orgId());
            if(managers.isEmpty())throw new ProcessRejectedException("P008 quota/attendance manager candidate is missing");
            if(reviewers.isEmpty())throw new ProcessRejectedException("P008 reviewer candidate is missing");
            BigDecimal duration=hours(command.startAt(),command.endAt());
            LeaveRecord record=new LeaveRecord(claim.resourceId(),actor.tenantId(),numbers.next(actor.tenantId(),actor.employeeId(),PROCESS_CODE),
                    null,null,"S01",label("S01"),0,command.subject().trim(),trim(command.reason()),actor.orgId(),actor.employeeId(),
                    command.attendanceType().trim(),command.startAt(),command.endAt(),duration,command.quotaAccountId().trim(),command.quotaAmount(),
                    trim(command.handoverAgentId()),trim(command.knownImpact()),null,null,null,null,null,null,null,null,null,null,null,null,Instant.now());
            repository.insert(record,actor.employeeId());
            ObjectNode context=mapper.createObjectNode();
            context.put("ownerEmployeeId",actor.employeeId().toString());context.put("ownerCenterId",actor.orgId().toString());
            context.set("targetEmployeeIds",uuidArray(List.of(actor.employeeId())));
            context.set("managerCandidateIds",uuidArray(managers));context.set("reviewCandidateIds",uuidArray(reviewers));
            WorkflowRuntimeService.Result started=workflow.start(new WorkflowRuntimeService.StartCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),version,
                    "attendance.leave_request",record.id(),record.businessNo(),record.subject(),"NORMAL",context,scope(key,"start")));
            forms.submit(new WorkflowFormService.SubmitForm(actor.tenantId(),actor.employeeId(),actor.identityId(),started.instance().id(),null,form.id(),form.versionNo(),List.of(
                    text("subject",record.subject()),text("attendance_type",record.attendanceType()),text("start_at",record.startAt().toString()),
                    text("end_at",record.endAt().toString()),text("quota_account_id",record.quotaAccountId()),text("quota_amount",record.quotaAmount().toPlainString()),
                    text("handover_agent_id",record.handoverAgentId()==null?"":record.handoverAgentId()),text("known_impact",record.knownImpact()==null?"":record.knownImpact())),scope(key,"form")));
            WorkflowRuntimeService.Result moved=workflow.act(new WorkflowRuntimeService.ActionCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),started.instance().id(),null,"S01","SUBMIT_LEAVE",null,scope(key,"s01")));
            if(repository.bindAndMove(actor.tenantId(),record.id(),0,moved.instance().id(),label("S02"),actor.employeeId())!=1)
                throw new ProcessRejectedException("P008 concurrent create conflict");
            emit(actor,record,"SUBMIT_LEAVE","S02",recipients(actor.employeeId(),managers,reviewers));
            return aggregate(actor.tenantId(),record.id());
        });
    }

    public Aggregate act(DatabaseSecurityContext actor,UUID id,String actionCode,String key,String requestHash,ActionCommand command){
        requireActor(actor);Objects.requireNonNull(command,"P008 action command is required");String action=safe(actionCode);
        return tx.required(actor,()->{
            Aggregate current=aggregate(actor.tenantId(),id);
            IdempotencyClaim claim=idempotency.claim(actor.tenantId(),actor.employeeId(),key,requestHash,"attendance.leave_request.action."+action.toLowerCase(Locale.ROOT),id,IDEMPOTENCY_TTL);
            if(claim.existing())return current;
            if(current.record().versionNo()!=command.expectedVersion())throw new ProcessRejectedException("P008 version conflict");
            String node=current.record().currentNodeCode();
            switch(node){
                case "S02"->{require(action,"RESERVE_QUOTA");repository.appendLedger(actor.tenantId(),id,"RESERVE",current.record().quotaAmount(),command.reason(),actor.employeeId());required(repository.markQuotaReserved(actor.tenantId(),id,actor.employeeId()),"P008 quota reservation failed");}
                case "S03"->{require(action,"CONFIRM_HANDOVER");requireSelf(actor,current.record());required(repository.markHandover(actor.tenantId(),id,actor.employeeId()),"P008 handover confirmation failed");}
                case "S04"->{if(!"APPROVE_LEAVE".equals(action)&&!"REJECT_LEAVE".equals(action))throw new ProcessRejectedException("P008 review action invalid");required(repository.markDecision(actor.tenantId(),id,"APPROVE_LEAVE".equals(action)?"APPROVED":"REJECTED",actor.employeeId()),"P008 decision update failed");}
                case "S05"->{String decision=current.record().decision();if("APPROVED".equals(decision)){require(action,"COMMIT_QUOTA");repository.appendLedger(actor.tenantId(),id,"DEDUCT",current.record().quotaAmount(),command.reason(),actor.employeeId());}else if("REJECTED".equals(decision)){require(action,"RELEASE_QUOTA");repository.appendLedger(actor.tenantId(),id,"RELEASE",current.record().quotaAmount(),command.reason(),actor.employeeId());}else throw new ProcessRejectedException("P008 approval decision missing");required(repository.markQuotaSettled(actor.tenantId(),id,actor.employeeId()),"P008 quota settlement failed");}
                case "S06"->{require(action,"MARK_ATTENDANCE");required(repository.markAttendance(actor.tenantId(),id,actor.employeeId()),"P008 attendance mark failed");}
                case "S07"->{require(action,"START_LEAVE");requireSelf(actor,current.record());required(repository.markLeaveStarted(actor.tenantId(),id,command.actualAt()==null?Instant.now():command.actualAt(),actor.employeeId()),"P008 actual leave start failed");}
                case "S08"->{if(!"RETURN_TO_WORK".equals(action)&&!"CHANGE_LEAVE".equals(action))throw new ProcessRejectedException("P008 return/change action invalid");requireSelf(actor,current.record());required(repository.markReturned(actor.tenantId(),id,command.actualAt()==null?Instant.now():command.actualAt(),actor.employeeId()),"P008 return fact failed");}
                case "S09"->{require(action,"ADJUST_QUOTA");BigDecimal adjustment=command.adjustmentAmount()==null?BigDecimal.ZERO:command.adjustmentAmount();repository.appendLedger(actor.tenantId(),id,"ADJUST",adjustment,command.reason(),actor.employeeId());required(repository.markQuotaAdjusted(actor.tenantId(),id,actor.employeeId()),"P008 quota adjustment failed");}
                case "S10"->{require(action,"CLOSE_DAY");required(repository.markDayClosed(actor.tenantId(),id,actor.employeeId()),"P008 day close failed");}
                default->throw new ProcessRejectedException("P008 action is not allowed from current source node");
            }
            current=aggregate(actor.tenantId(),id);
            LeaveRecord moved=advance(actor,current.record(),node,action,scope(key,"workflow"),command.reason());
            return aggregate(actor.tenantId(),moved.id());
        });
    }

    public Optional<Aggregate> find(DatabaseSecurityContext actor,UUID id){requireActor(actor);return tx.required(actor,()->repository.find(actor.tenantId(),id).map(Aggregate::new));}
    public List<Aggregate> list(DatabaseSecurityContext actor){requireActor(actor);return tx.required(actor,()->repository.list(actor.tenantId()).stream().map(Aggregate::new).toList());}
    public List<LedgerEntry> ledger(DatabaseSecurityContext actor){requireActor(actor);return tx.required(actor,()->repository.ledger(actor.tenantId()));}

    private LeaveRecord advance(DatabaseSecurityContext actor,LeaveRecord record,String sourceNode,String action,String key,String reason){
        WorkflowRuntimeService.Result runtime=workflow.get(actor.tenantId(),record.workflowInstanceId());
        if(!sourceNode.equals(runtime.instance().currentNodeCode())||!sourceNode.equals(record.currentNodeCode()))throw new ProcessRejectedException("P008 stale workflow projection");
        if(runtime.task()==null)throw new ProcessRejectedException("P008 workflow task missing");
        tasks.claim(new WorkflowTaskAssignmentService.ClaimCommand(actor.tenantId(),runtime.task().id(),actor.employeeId()));
        WorkflowRuntimeService.Result moved=workflow.act(new WorkflowRuntimeService.ActionCommand(actor.tenantId(),actor.employeeId(),actor.identityId(),record.workflowInstanceId(),runtime.task().id(),sourceNode,action,reason,key));
        Instant closed="END".equals(moved.instance().currentNodeCode())?moved.instance().finishedAt():null;
        if(repository.moveStatus(actor.tenantId(),record.id(),record.versionNo(),label(moved.instance().currentNodeCode()),closed,actor.employeeId())!=1)
            throw new ProcessRejectedException("P008 concurrent status transition conflict");
        LeaveRecord result=repository.find(actor.tenantId(),record.id()).orElseThrow();
        emit(actor,result,action,moved.instance().currentNodeCode(),List.of(record.ownerEmployeeId()));return result;
    }

    private Aggregate aggregate(UUID tenant,UUID id){return new Aggregate(repository.find(tenant,id).orElseThrow(()->new ProcessRejectedException("P008 leave request not found")));}
    private void emit(DatabaseSecurityContext actor,LeaveRecord record,String event,String node,List<UUID> recipients){ObjectNode p=mapper.createObjectNode();p.put("leaveRequestId",record.id().toString());p.put("businessNo",record.businessNo());p.put("event",event);p.put("nodeCode",node);p.set("recipientEmployeeIds",uuidArray(recipients));outbox.enqueue(new TransactionalOutboxService.Command(actor.tenantId(),actor.employeeId(),"P008_LEAVE",record.id(),"P008_LEAVE_EVENT",1,json(p),"p008:"+record.id()+":"+record.versionNo()));}

    private static void validateCreate(CreateCommand c){Objects.requireNonNull(c,"P008 create command is required");req(c.subject(),"subject");req(c.attendanceType(),"attendanceType");req(c.quotaAccountId(),"quotaAccountId");if(c.ownerCenterId()==null)throw new ProcessRejectedException("P008 ownerCenterId is required");if(c.quotaAmount()==null||c.quotaAmount().signum()<=0)throw new ProcessRejectedException("P008 quotaAmount must be positive");hours(c.startAt(),c.endAt());}
    private static BigDecimal hours(Instant start,Instant end){if(start==null||end==null||!end.isAfter(start))throw new ProcessRejectedException("P008 endAt must be after startAt");return BigDecimal.valueOf(Duration.between(start,end).toMinutes()).divide(BigDecimal.valueOf(60),6,RoundingMode.HALF_UP);}
    private static void requireSelf(DatabaseSecurityContext actor,LeaveRecord r){if(!actor.employeeId().equals(r.ownerEmployeeId()))throw new ProcessRejectedException("P008 employee action is self-only");}
    private static void required(int updated,String message){if(updated!=1)throw new ProcessRejectedException(message);}
    private static void require(String actual,String expected){if(!expected.equals(actual))throw new ProcessRejectedException("P008 action not allowed from current source node");}
    private static void requireActor(DatabaseSecurityContext a){if(a==null||a.tenantId()==null||a.employeeId()==null||a.identityId()==null||a.orgId()==null||a.userId()==null||a.positionId()==null)throw new ProcessRejectedException("P008 authenticated employee context required");}
    private static void req(String value,String field){if(value==null||value.isBlank())throw new ProcessRejectedException("P008 required field missing: "+field);}
    private static String safe(String value){if(value==null)return"";String x=value.trim().toUpperCase(Locale.ROOT);return x.matches("[A-Z0-9_]{1,32}")?x:"";}
    private static String scope(String key,String suffix){if(key==null||key.isBlank())throw new ProcessRejectedException("P008 idempotency key required");String x=key+":"+suffix;if(x.length()>128)throw new ProcessRejectedException("P008 idempotency key too long");return x;}
    private static String trim(String value){return value==null||value.isBlank()?null:value.trim();}
    private static WorkflowFormService.FieldValue text(String code,String value){return new WorkflowFormService.FieldValue(code,"TEXT",value,null,null,null,null,null,"P1",false);}
    private ArrayNode uuidArray(List<UUID> ids){ArrayNode a=mapper.createArrayNode();ids.stream().filter(Objects::nonNull).distinct().forEach(x->a.add(x.toString()));return a;}
    @SafeVarargs private static List<UUID> recipients(UUID employee,List<UUID>... groups){List<UUID> values=new ArrayList<>();values.add(employee);for(List<UUID> group:groups)values.addAll(group);return values.stream().filter(Objects::nonNull).distinct().toList();}
    private String json(JsonNode node){try{return mapper.writeValueAsString(node);}catch(JsonProcessingException e){throw new ProcessRejectedException("P008 JSON serialization failed",e);}}

    public static String label(String node){return switch(node){case"S01"->"请假申请";case"S02"->"假期额度预占";case"S03"->"工作交接与代理";case"S04"->"审批";case"S05"->"预占转扣减/驳回释放";case"S06"->"排班与考勤标记";case"S07"->"实际休假";case"S08"->"销假/提前返岗/变更";case"S09"->"差额账本调整";case"S10"->"考勤日结与归档";case"END"->"已关闭";default->throw new ProcessRejectedException("P008 unknown workflow node: "+node);};}

    public interface Repository{
        Optional<UUID> workflowVersion(UUID tenantId);Optional<FormRef> form(UUID tenantId);List<UUID> permissionCandidates(UUID tenantId,String permission,UUID orgId);
        boolean hasTimeConflict(UUID tenantId,UUID employeeId,Instant start,Instant end);void insert(LeaveRecord record,UUID actor);int bindAndMove(UUID tenantId,UUID id,int version,UUID workflowId,String status,UUID actor);int moveStatus(UUID tenantId,UUID id,int version,String status,Instant closedAt,UUID actor);
        int markQuotaReserved(UUID tenantId,UUID id,UUID actor);int markHandover(UUID tenantId,UUID id,UUID actor);int markDecision(UUID tenantId,UUID id,String decision,UUID actor);int markQuotaSettled(UUID tenantId,UUID id,UUID actor);int markAttendance(UUID tenantId,UUID id,UUID actor);int markLeaveStarted(UUID tenantId,UUID id,Instant actualAt,UUID actor);int markReturned(UUID tenantId,UUID id,Instant actualAt,UUID actor);int markQuotaAdjusted(UUID tenantId,UUID id,UUID actor);int markDayClosed(UUID tenantId,UUID id,UUID actor);
        void appendLedger(UUID tenantId,UUID id,String entryType,BigDecimal amount,String note,UUID actor);Optional<LeaveRecord> find(UUID tenantId,UUID id);List<LeaveRecord> list(UUID tenantId);List<LedgerEntry> ledger(UUID tenantId);
    }
    public record FormRef(UUID id,int versionNo){}
    public record CreateCommand(String subject,String reason,UUID ownerCenterId,String attendanceType,String quotaAccountId,BigDecimal quotaAmount,Instant startAt,Instant endAt,String handoverAgentId,String knownImpact){}
    public record ActionCommand(int expectedVersion,String reason,Instant actualAt,BigDecimal adjustmentAmount){}
    public record LeaveRecord(UUID id,UUID tenantId,String businessNo,UUID workflowInstanceId,String workflowInstanceNo,String currentNodeCode,String status,int versionNo,String subject,String reason,UUID ownerCenterId,UUID ownerEmployeeId,String attendanceType,Instant startAt,Instant endAt,BigDecimal durationHours,String quotaAccountId,BigDecimal quotaAmount,String handoverAgentId,String knownImpact,Instant quotaReservedAt,Instant handoverConfirmedAt,String decision,Instant approvedAt,Instant rejectedAt,Instant quotaSettledAt,Instant attendanceMarkedAt,Instant leaveStartedAt,Instant returnedAt,Instant quotaAdjustedAt,Instant dayClosedAt,Instant closedAt,Instant updatedAt){}
    public record LedgerEntry(UUID id,UUID leaveRequestId,String businessNo,UUID ownerCenterId,UUID ownerEmployeeId,int sequence,String entryType,BigDecimal amount,String note,Instant createdAt){public LedgerEntry metadataOnly(){return new LedgerEntry(id,leaveRequestId,businessNo,ownerCenterId,null,sequence,entryType,null,null,createdAt);}}
    public record Aggregate(LeaveRecord record){public Aggregate metadataOnly(){LeaveRecord r=record;return new Aggregate(new LeaveRecord(r.id(),r.tenantId(),r.businessNo(),r.workflowInstanceId(),r.workflowInstanceNo(),r.currentNodeCode(),r.status(),r.versionNo(),null,null,r.ownerCenterId(),null,r.attendanceType(),r.startAt(),r.endAt(),r.durationHours(),null,null,null,null,r.quotaReservedAt(),r.handoverConfirmedAt(),r.decision(),r.approvedAt(),r.rejectedAt(),r.quotaSettledAt(),r.attendanceMarkedAt(),r.leaveStartedAt(),r.returnedAt(),r.quotaAdjustedAt(),r.dayClosedAt(),r.closedAt(),r.updatedAt()));}}
}
