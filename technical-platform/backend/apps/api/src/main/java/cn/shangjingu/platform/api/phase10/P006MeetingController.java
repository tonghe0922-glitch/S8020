package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.MeetingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

/** PHASE-10 engineering HTTP contract for P006 meeting and action items. */
@RestController
@RequestMapping("/api/v1/processes/P006/meetings")
public final class P006MeetingController {
    public static final String CREATE = "p006.meeting.create";
    public static final String READ = "p006.meeting.read";
    public static final String MANAGE = "p006.meeting.manage";
    public static final String ACTION = "p006.meeting.action";
    public static final String ACCEPT = "p006.meeting.accept";
    public static final String MONITOR = "p006.meeting.monitor";

    private final MeetingService meetings;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P006MeetingController(MeetingService meetings, AuthorizationService authorization,
            JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.meetings=meetings; this.authorization=authorization; this.audit=audit; this.mapper=mapper;
    }

    @PostMapping
    public MeetingService.MeetingAggregate create(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MeetingService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(),CREATE));
        require(authorization.authorizeData(principal.context(),CREATE,new AuthorizationTarget(
                principal.context().tenantId(),principal.context().employeeId(),command.ownerCenterId(),
                principal.context().positionId(),principal.context().employeeId())));
        audit.recordOperation(principal.context(),"P006_CREATE_ATTEMPT","collaboration.meeting",null);
        var result=meetings.create(context(principal),idempotencyKey,hash(command),command);
        audit.recordOperation(principal.context(),"P006_CREATED","collaboration.meeting",result.meeting().id());
        return result;
    }

    @GetMapping
    public List<MeetingService.MeetingAggregate> list(@AuthenticationPrincipal SessionPrincipal principal) {
        boolean read=allowed(principal,READ), manage=allowed(principal,MANAGE), monitor=allowed(principal,MONITOR);
        if(!read&&!manage&&!monitor) throw denied("no P006 read surface is granted");
        List<MeetingService.MeetingAggregate> result=meetings.list(context(principal)).stream()
                .map(a->project(principal,a,read,manage,monitor)).filter(java.util.Objects::nonNull).toList();
        audit.recordOperation(principal.context(),"P006_LIST","collaboration.meeting",null);
        return result;
    }

    @GetMapping("/{id}")
    public MeetingService.MeetingAggregate get(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable UUID id) {
        boolean read=allowed(principal,READ), manage=allowed(principal,MANAGE), monitor=allowed(principal,MONITOR);
        if(!read&&!manage&&!monitor) throw denied("no P006 read surface is granted");
        var aggregate=required(principal,id);
        var projected=project(principal,aggregate,read,manage,monitor);
        if(projected==null) throw denied("P006 data scope denied");
        audit.recordOperation(principal.context(),"P006_READ","collaboration.meeting",id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public MeetingService.MeetingAggregate action(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable UUID id,
            @PathVariable String actionCode,@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MeetingService.ActionCommand command) {
        String action=safeAction(actionCode);
        String permission=permission(action);
        require(authorization.authorizeAction(principal.context(),permission));
        var current=required(principal,id);
        boolean participantAction=ACTION.equals(permission);
        if(participantAction && !current.employeeVisible(principal.context().employeeId()))
            throw denied("P006 employee is not a participant/action owner");
        AuthorizationTarget authorizationTarget=participantAction
                ? participantTarget(current.meeting(),principal)
                : target(current.meeting(),principal);
        require(authorization.authorizeData(principal.context(),permission,authorizationTarget));
        audit.recordOperation(principal.context(),"P006_ACTION_ATTEMPT_"+action,"collaboration.meeting",id);
        var result=meetings.act(context(principal),id,action,idempotencyKey,hash(Map.of("actionCode",action,"body",command)),command);
        audit.recordOperation(principal.context(),"P006_ACTION_"+action,"collaboration.meeting",id);
        return result;
    }

    private MeetingService.MeetingAggregate project(SessionPrincipal principal,MeetingService.MeetingAggregate a,
            boolean read,boolean manage,boolean monitor) {
        AuthorizationTarget target=target(a.meeting(),principal);
        if(manage && authorization.authorizeData(principal.context(),MANAGE,target).allowed()) return a;
        if(read && a.employeeVisible(principal.context().employeeId())
                && authorization.authorizeData(principal.context(),READ,participantTarget(a.meeting(),principal)).allowed()) return a;
        if(monitor && authorization.authorizeData(principal.context(),MONITOR,target).allowed()) return a.metadataOnly();
        return null;
    }
    private MeetingService.MeetingAggregate required(SessionPrincipal p,UUID id) {
        return meetings.find(context(p),id).orElseThrow(()->new IllegalArgumentException("P006 meeting not found"));
    }
    private static String permission(String action) {
        if("ATTEND".equals(action)||"LEAVE".equals(action)||"SUBMIT_ACTION_EVIDENCE".equals(action)) return ACTION;
        if("ACCEPT_ACTIONS".equals(action)||"RETURN_ACTIONS".equals(action)) return ACCEPT;
        return MANAGE;
    }
    private static AuthorizationTarget target(MeetingService.Meeting m,SessionPrincipal p) {
        return new AuthorizationTarget(m.tenantId(),m.ownerEmployeeId(),m.ownerCenterId(),p.context().positionId(),m.ownerEmployeeId());
    }
    static AuthorizationTarget participantTarget(MeetingService.Meeting m,SessionPrincipal p) {
        return new AuthorizationTarget(m.tenantId(),p.context().employeeId(),m.ownerCenterId(),p.context().positionId(),m.ownerEmployeeId());
    }
    private boolean allowed(SessionPrincipal p,String permission) { return authorization.authorizeAction(p.context(),permission).allowed(); }
    private static DatabaseSecurityContext context(SessionPrincipal p) { var s=p.context(); return new DatabaseSecurityContext(s.tenantId(),s.userId(),s.identityId(),s.employeeId(),s.appointmentId(),s.orgId(),s.positionId()); }
    private static void require(AuthorizationDecision d) { if(!d.allowed()) throw denied(d.reason().toString()); }
    private static AccessDeniedException denied(String reason) { return new AccessDeniedException("P006 authorization denied: "+reason); }
    private static String safeAction(String code) { if(code==null)return "INVALID"; String v=code.trim().toUpperCase(); return v.matches("[A-Z0-9_]{1,32}")?v:"INVALID"; }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch(Exception ex){ throw new IllegalArgumentException("P006 request cannot be hashed",ex); }
    }
}
