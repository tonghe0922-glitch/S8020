package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.ShiftChangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/processes/P007")
public final class P007ShiftController {
    public static final String READ="p007.schedule.read",MANAGE="p007.schedule.manage",CHANGE="p007.schedule.change",REVIEW="p007.schedule.review",MONITOR="p007.schedule.monitor";
    private final ShiftChangeService shifts;private final AuthorizationService auth;private final JdbcSecurityAuditService audit;private final ObjectMapper mapper;
    public P007ShiftController(ShiftChangeService shifts,AuthorizationService auth,JdbcSecurityAuditService audit,ObjectMapper mapper){this.shifts=shifts;this.auth=auth;this.audit=audit;this.mapper=mapper;}

    @GetMapping("/schedules") public List<ShiftChangeService.Aggregate> schedules(@AuthenticationPrincipal SessionPrincipal p){return listVisible(p);}
    @PostMapping("/shift-changes")
    public ShiftChangeService.Aggregate create(@AuthenticationPrincipal SessionPrincipal p,@RequestHeader("Idempotency-Key")String key,@RequestBody ShiftChangeService.CreateCommand c){
        String permission=p.context().employeeId().equals(c.targetEmployeeId())?CHANGE:MANAGE;
        require(auth.authorizeAction(p.context(),permission));
        require(auth.authorizeData(p.context(),permission,new AuthorizationTarget(p.context().tenantId(),c.targetEmployeeId(),c.ownerCenterId(),p.context().positionId(),c.targetEmployeeId())));
        audit.recordOperation(p.context(),"P007_CREATE_ATTEMPT","attendance.shift_change_request",null);
        var r=shifts.create(ctx(p),key,hash(c),c);audit.recordOperation(p.context(),"P007_CREATED","attendance.shift_change_request",r.record().id());return r;
    }
    @GetMapping("/shift-changes") public List<ShiftChangeService.Aggregate> shiftChanges(@AuthenticationPrincipal SessionPrincipal p){return listVisible(p);}
    @GetMapping("/shift-changes/{id}") public ShiftChangeService.Aggregate get(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID id){var r=shifts.find(ctx(p),id).orElseThrow(()->new IllegalArgumentException("P007 record not found"));var v=project(p,r);if(v==null)throw denied("P007 data scope denied");audit.recordOperation(p.context(),"P007_READ","attendance.shift_change_request",id);return v;}
    @PostMapping("/shift-changes/{id}/actions/{actionCode}")
    public ShiftChangeService.Aggregate action(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID id,@PathVariable String actionCode,@RequestHeader("Idempotency-Key")String key,@RequestBody ShiftChangeService.ActionCommand c){String action=safe(actionCode);String perm=permission(action);require(auth.authorizeAction(p.context(),perm));var current=shifts.find(ctx(p),id).orElseThrow(()->new IllegalArgumentException("P007 record not found"));require(auth.authorizeData(p.context(),perm,target(current.record(),p)));if(CHANGE.equals(perm)&&!p.context().employeeId().equals(current.record().targetEmployeeId())&&!allowed(p,MANAGE))throw denied("P007 employee change is self-only");audit.recordOperation(p.context(),"P007_ACTION_ATTEMPT_"+action,"attendance.shift_change_request",id);var r=shifts.act(ctx(p),id,action,key,hash(Map.of("action",action,"body",c)),c);audit.recordOperation(p.context(),"P007_ACTION_"+action,"attendance.shift_change_request",id);return r;}

    private List<ShiftChangeService.Aggregate> listVisible(SessionPrincipal p){boolean read=allowed(p,READ),manage=allowed(p,MANAGE),change=allowed(p,CHANGE),monitor=allowed(p,MONITOR);if(!read&&!manage&&!change&&!monitor)throw denied("no P007 read surface");List<ShiftChangeService.Aggregate> result=shifts.list(ctx(p)).stream().map(r->project(p,r)).filter(java.util.Objects::nonNull).toList();audit.recordOperation(p.context(),"P007_LIST","attendance.shift_change_request",null);return result;}
    private ShiftChangeService.Aggregate project(SessionPrincipal p,ShiftChangeService.Aggregate a){var r=a.record();if(allowed(p,MANAGE)&&auth.authorizeData(p.context(),MANAGE,target(r,p)).allowed())return a;if((allowed(p,READ)||allowed(p,CHANGE))&&p.context().employeeId().equals(r.targetEmployeeId())&&auth.authorizeData(p.context(),READ,target(r,p)).allowed())return a;if(allowed(p,MONITOR)&&auth.authorizeData(p.context(),MONITOR,target(r,p)).allowed())return a.metadataOnly();return null;}
    private static String permission(String a){return switch(a){case"CONFIRM_SCHEDULE","SUBMIT_SHIFT_CHANGE"->CHANGE;case"APPROVE_CHANGE","RETURN_CHANGE"->REVIEW;default->MANAGE;};}
    private static AuthorizationTarget target(ShiftChangeService.ShiftRecord r,SessionPrincipal p){return new AuthorizationTarget(r.tenantId(),r.targetEmployeeId(),r.ownerCenterId(),p.context().positionId(),r.targetEmployeeId());}
    private boolean allowed(SessionPrincipal p,String perm){return auth.authorizeAction(p.context(),perm).allowed();}
    private static DatabaseSecurityContext ctx(SessionPrincipal p){var s=p.context();return new DatabaseSecurityContext(s.tenantId(),s.userId(),s.identityId(),s.employeeId(),s.appointmentId(),s.orgId(),s.positionId());}
    private static void require(AuthorizationDecision d){if(!d.allowed())throw denied(d.reason().toString());}private static AccessDeniedException denied(String r){return new AccessDeniedException("P007 authorization denied: "+r);}private static String safe(String a){if(a==null)return"INVALID";String x=a.trim().toUpperCase(Locale.ROOT);return x.matches("[A-Z0-9_]{1,32}")?x:"INVALID";}
    private String hash(Object v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(v)));}catch(Exception e){throw new IllegalArgumentException("P007 request cannot be hashed",e);}}
}
