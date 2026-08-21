package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.OvertimeService;
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
@RequestMapping("/api/v1/processes/P009")
public final class P009OvertimeController {
    public static final String SUBMIT="p009.overtime.submit",READ="p009.overtime.read",REVIEW="p009.overtime.review",HR="p009.overtime.hr",MANAGE="p009.overtime.manage",MONITOR="p009.overtime.monitor";
    private final OvertimeService overtime;private final AuthorizationService auth;private final JdbcSecurityAuditService audit;private final ObjectMapper mapper;
    public P009OvertimeController(OvertimeService overtime,AuthorizationService auth,JdbcSecurityAuditService audit,ObjectMapper mapper){this.overtime=overtime;this.auth=auth;this.audit=audit;this.mapper=mapper;}
    @PostMapping("/overtime-requests") public OvertimeService.Aggregate create(@AuthenticationPrincipal SessionPrincipal p,@RequestHeader("Idempotency-Key")String key,@RequestBody OvertimeService.CreateCommand c){require(auth.authorizeAction(p.context(),SUBMIT));require(auth.authorizeData(p.context(),SUBMIT,new AuthorizationTarget(p.context().tenantId(),p.context().employeeId(),c.ownerCenterId(),p.context().positionId(),p.context().employeeId())));audit.recordOperation(p.context(),"P009_CREATE_ATTEMPT","attendance.overtime_request",null);var r=overtime.create(ctx(p),key,hash(c),c);audit.recordOperation(p.context(),"P009_CREATED","attendance.overtime_request",r.record().id());return r;}
    @GetMapping("/overtime-requests") public List<OvertimeService.Aggregate> list(@AuthenticationPrincipal SessionPrincipal p){boolean read=allowed(p,READ),manage=allowed(p,MANAGE),review=allowed(p,REVIEW),hr=allowed(p,HR),monitor=allowed(p,MONITOR);if(!read&&!manage&&!review&&!hr&&!monitor)throw denied("no P009 read surface");List<OvertimeService.Aggregate> result=overtime.list(ctx(p)).stream().map(a->project(p,a,read,manage,review,hr,monitor)).filter(java.util.Objects::nonNull).toList();audit.recordOperation(p.context(),"P009_LIST","attendance.overtime_request",null);return result;}
    @GetMapping("/overtime-requests/{id}") public OvertimeService.Aggregate get(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID id){boolean read=allowed(p,READ),manage=allowed(p,MANAGE),review=allowed(p,REVIEW),hr=allowed(p,HR),monitor=allowed(p,MONITOR);if(!read&&!manage&&!review&&!hr&&!monitor)throw denied("no P009 read surface");var a=overtime.find(ctx(p),id).orElseThrow(()->new IllegalArgumentException("P009 overtime not found"));var v=project(p,a,read,manage,review,hr,monitor);if(v==null)throw denied("P009 data scope denied");audit.recordOperation(p.context(),"P009_READ","attendance.overtime_request",id);return v;}
    @PostMapping("/overtime-requests/{id}/actions/{actionCode}") public OvertimeService.Aggregate action(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID id,@PathVariable String actionCode,@RequestHeader("Idempotency-Key")String key,@RequestBody OvertimeService.ActionCommand c){String action=safe(actionCode);String permission=permission(action);require(auth.authorizeAction(p.context(),permission));var current=overtime.find(ctx(p),id).orElseThrow(()->new IllegalArgumentException("P009 overtime not found"));require(auth.authorizeData(p.context(),permission,target(current.record(),p)));if(SUBMIT.equals(permission)&&!p.context().employeeId().equals(current.record().ownerEmployeeId()))throw denied("P009 employee action is self-only");audit.recordOperation(p.context(),"P009_ACTION_ATTEMPT_"+action,"attendance.overtime_request",id);var r=overtime.act(ctx(p),id,action,key,hash(Map.of("action",action,"body",c)),c);audit.recordOperation(p.context(),"P009_ACTION_"+action,"attendance.overtime_request",id);return r;}
    private OvertimeService.Aggregate project(SessionPrincipal p,OvertimeService.Aggregate a,boolean read,boolean manage,boolean review,boolean hr,boolean monitor){AuthorizationTarget t=target(a.record(),p);if(manage&&auth.authorizeData(p.context(),MANAGE,t).allowed())return a;if(review&&auth.authorizeData(p.context(),REVIEW,t).allowed())return a;if(hr&&auth.authorizeData(p.context(),HR,t).allowed())return a;if(read&&p.context().employeeId().equals(a.record().ownerEmployeeId())&&auth.authorizeData(p.context(),READ,t).allowed())return a;if(monitor&&auth.authorizeData(p.context(),MONITOR,t).allowed())return a.metadataOnly();return null;}
    private static String permission(String action){return switch(action){case"RECORD_ACTUAL_FACT"->SUBMIT;case"APPROVE_OVERTIME","REJECT_OVERTIME"->REVIEW;case"HR_REVIEW","SET_COMPENSATION_PLAN","ACK_PAYROLL_RECEIPT","ARCHIVE"->HR;default->MANAGE;};}
    private static AuthorizationTarget target(OvertimeService.OvertimeRecord r,SessionPrincipal p){return new AuthorizationTarget(r.tenantId(),r.ownerEmployeeId(),r.ownerCenterId(),p.context().positionId(),r.ownerEmployeeId());}
    private boolean allowed(SessionPrincipal p,String perm){return auth.authorizeAction(p.context(),perm).allowed();}private static DatabaseSecurityContext ctx(SessionPrincipal p){var s=p.context();return new DatabaseSecurityContext(s.tenantId(),s.userId(),s.identityId(),s.employeeId(),s.appointmentId(),s.orgId(),s.positionId());}private static void require(AuthorizationDecision d){if(!d.allowed())throw denied(d.reason().toString());}private static AccessDeniedException denied(String r){return new AccessDeniedException("P009 authorization denied: "+r);}private static String safe(String a){if(a==null)return"INVALID";String x=a.trim().toUpperCase(Locale.ROOT);return x.matches("[A-Z0-9_]{1,32}")?x:"INVALID";}private String hash(Object v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(v)));}catch(Exception e){throw new IllegalArgumentException("P009 request cannot be hashed",e);}}
}
