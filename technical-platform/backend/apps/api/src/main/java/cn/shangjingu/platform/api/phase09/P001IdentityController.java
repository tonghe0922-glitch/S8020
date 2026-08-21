package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.LoginService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.iam.mfa.TotpCredentialService;
import cn.shangjingu.platform.iam.session.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/processes/P001")
public final class P001IdentityController {
    private static final String MONITOR="p001.session.monitor";
    private final TotpCredentialService totp; private final SessionService sessions; private final AuthorizationService authorization; private final JdbcSecurityAuditService audit; private final ObjectMapper mapper; private final LoginService login;
    public P001IdentityController(TotpCredentialService totp,SessionService sessions,AuthorizationService authorization,JdbcSecurityAuditService audit,ObjectMapper mapper,LoginService login){this.totp=totp;this.sessions=sessions;this.authorization=authorization;this.audit=audit;this.mapper=mapper;this.login=login;}
    @GetMapping("/mfa/totp") public TotpCredentialService.MfaStatus mfaStatus(@AuthenticationPrincipal SessionPrincipal p){var result=totp.status(ctx(p));audit.recordOperation(p.context(),"P001_MFA_STATUS","iam.mfa_credential",null);return result;}
    @PostMapping("/mfa/totp/enroll") public TotpCredentialService.Enrollment enroll(@AuthenticationPrincipal SessionPrincipal p,@RequestHeader("Idempotency-Key")String key,@RequestBody EnrollRequest r){login.requirePasswordReauthentication(p.context().tenantId(),p.context().userId(),r.password());audit.recordOperation(p.context(),"P001_REAUTH_SUCCESS","SESSION",null);audit.recordOperation(p.context(),"P001_MFA_ENROLL_ATTEMPT","iam.mfa_credential",null);return totp.enroll(ctx(p),key,hash(new EnrollFingerprint(r.issuer(),r.accountName())),r.issuer(),r.accountName());}
    @PostMapping("/mfa/totp/confirm") public TotpCredentialService.Credential confirm(@AuthenticationPrincipal SessionPrincipal p,@RequestHeader("Idempotency-Key")String key,@RequestBody ConfirmRequest r){audit.recordOperation(p.context(),"P001_MFA_CONFIRM_ATTEMPT","iam.mfa_credential",null);var result=totp.confirm(ctx(p),key,hash(r),r.expectedVersion(),r.code());audit.recordOperation(p.context(),"P001_MFA_CONFIRMED","iam.mfa_credential",result.id());return redact(result);}
    @DeleteMapping("/mfa/totp") public ResponseEntity<Void> disable(@AuthenticationPrincipal SessionPrincipal p,@RequestHeader("Idempotency-Key")String key,@RequestBody ConfirmRequest r){audit.recordOperation(p.context(),"P001_MFA_DISABLE_ATTEMPT","iam.mfa_credential",null);totp.disable(ctx(p),key,hash(r),r.expectedVersion(),r.code());audit.recordOperation(p.context(),"P001_MFA_DISABLED","iam.mfa_credential",null);return ResponseEntity.noContent().build();}
    @GetMapping("/sessions") public List<SessionService.SessionSummary> sessions(@AuthenticationPrincipal SessionPrincipal p,@RequestParam(required=false)UUID userId){UUID target=userId==null?p.context().userId():userId;if(!target.equals(p.context().userId()))require(authorization.authorizeAction(p.context(),MONITOR));List<SessionService.SessionSummary> result=sessions.listActive(p.context().tenantId(),target);if(!target.equals(p.context().userId()))result=result.stream().filter(s->authorization.authorizeData(p.context(),MONITOR,new AuthorizationTarget(p.context().tenantId(),s.employeeId(),s.orgId(),null,s.employeeId())).allowed()).toList();audit.recordOperation(p.context(),"P001_SESSION_LIST","SESSION",null);return result;}
    private TotpCredentialService.Credential redact(TotpCredentialService.Credential c){return new TotpCredentialService.Credential(c.id(),c.tenantId(),c.userId(),c.method(),new byte[0],c.status(),c.versionNo(),c.confirmedAt(),c.disabledAt());}
    private static DatabaseSecurityContext ctx(SessionPrincipal p){var s=p.context();return new DatabaseSecurityContext(s.tenantId(),s.userId(),s.identityId(),s.employeeId(),s.appointmentId(),s.orgId(),s.positionId());}
    private static void require(AuthorizationDecision d){if(!d.allowed())throw new AccessDeniedException("P001 authorization denied: "+d.reason());}
    private String hash(Object o){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(o)));}catch(Exception ex){throw new IllegalArgumentException("request cannot be hashed",ex);}}
    public record EnrollRequest(String issuer,String accountName,String password){} private record EnrollFingerprint(String issuer,String accountName){} public record ConfirmRequest(int expectedVersion,String code){}
}
