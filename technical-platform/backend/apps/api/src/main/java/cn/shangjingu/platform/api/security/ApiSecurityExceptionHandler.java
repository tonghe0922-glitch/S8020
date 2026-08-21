package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.iam.mfa.MfaRejectedException;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import cn.shangjingu.platform.iam.stepup.StepUpRejectedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiSecurityExceptionHandler {
    @ExceptionHandler(LoginRejectedException.class) ResponseEntity<Map<String,Object>> loginRejected(LoginRejectedException ex){return problem(HttpStatus.UNAUTHORIZED,"authentication_rejected","Authentication failed.");}
    @ExceptionHandler(SessionRejectedException.class) ResponseEntity<Map<String,Object>> sessionRejected(SessionRejectedException ex){HttpStatus status=switch(ex.reason()){case INVALID_ACCESS,INVALID_REFRESH,REFRESH_REPLAY->HttpStatus.UNAUTHORIZED;case IDENTITY_INACTIVE,APPOINTMENT_INACTIVE->HttpStatus.FORBIDDEN;case SESSION_CONFLICT->HttpStatus.CONFLICT;};return problem(status,"session_rejected","The session operation was rejected.");}
    @ExceptionHandler(StepUpRejectedException.class) ResponseEntity<Map<String,Object>> stepUpRejected(StepUpRejectedException ex){HttpStatus status=switch(ex.reason()){case AUDIT_UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;case TICKET_CONFLICT->HttpStatus.CONFLICT;default->HttpStatus.FORBIDDEN;};return problem(status,"step_up_rejected","The Step-Up operation was rejected.");}
    @ExceptionHandler(MfaRejectedException.class) ResponseEntity<Map<String,Object>> mfaRejected(MfaRejectedException ex){HttpStatus status=switch(ex.reason()){case INVALID_REQUEST->HttpStatus.BAD_REQUEST;case NOT_FOUND->HttpStatus.NOT_FOUND;case ASSERTION_REJECTED->HttpStatus.FORBIDDEN;case CONFLICT->HttpStatus.CONFLICT;case UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;};return problem(status,"mfa_rejected","The MFA operation was rejected.");}
    @ExceptionHandler(ProcessRejectedException.class) ResponseEntity<Map<String,Object>> processRejected(ProcessRejectedException ex){return problem(HttpStatus.CONFLICT,"process_rejected","The process operation was rejected.");}
    @ExceptionHandler(SecurityAuditUnavailableException.class) ResponseEntity<Map<String,Object>> auditUnavailable(SecurityAuditUnavailableException ex){return problem(HttpStatus.SERVICE_UNAVAILABLE,"security_audit_unavailable","Security audit persistence is unavailable.");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,Object>> invalidInput(IllegalArgumentException ex){return problem(HttpStatus.BAD_REQUEST,"invalid_request","The request is invalid.");}
    private ResponseEntity<Map<String,Object>> problem(HttpStatus status,String code,String detail){Map<String,Object> body=new LinkedHashMap<>();body.put("status",status.value());body.put("code",code);body.put("detail",detail);RequestAuditContext context=RequestAuditContext.current();body.put("requestId",context==null?UUID.randomUUID().toString():context.requestId());return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);}
}
