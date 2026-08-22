package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.iam.mfa.MfaRejectedException;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import cn.shangjingu.platform.iam.session.SessionStoreUnavailableException;
import cn.shangjingu.platform.iam.stepup.StepUpRejectedException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiSecurityExceptionHandler {
    private final ApiProblemSupport problems;

    public ApiSecurityExceptionHandler(ApiProblemSupport problems) {
        this.problems = problems;
    }

    @ExceptionHandler(LoginRejectedException.class)
    ResponseEntity<Map<String, Object>> loginRejected(LoginRejectedException exception) {
        return problems.response(HttpStatus.UNAUTHORIZED, "authentication_rejected", "Authentication failed.");
    }

    @ExceptionHandler(SessionRejectedException.class)
    ResponseEntity<Map<String, Object>> sessionRejected(SessionRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case INVALID_ACCESS, INVALID_REFRESH, REFRESH_REPLAY -> HttpStatus.UNAUTHORIZED;
                    case IDENTITY_INACTIVE, APPOINTMENT_INACTIVE -> HttpStatus.FORBIDDEN;
                    case SESSION_CONFLICT -> HttpStatus.CONFLICT;
                };
        return problems.response(status, "session_rejected", "The session operation was rejected.");
    }

    @ExceptionHandler(SessionStoreUnavailableException.class)
    ResponseEntity<Map<String, Object>> sessionStoreUnavailable(SessionStoreUnavailableException exception) {
        return problems.response(
                HttpStatus.SERVICE_UNAVAILABLE, "session_store_unavailable", "Session storage is unavailable.");
    }

    @ExceptionHandler(StepUpRejectedException.class)
    ResponseEntity<Map<String, Object>> stepUpRejected(StepUpRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case AUDIT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case TICKET_CONFLICT -> HttpStatus.CONFLICT;
                    default -> HttpStatus.FORBIDDEN;
                };
        return problems.response(status, "step_up_rejected", "The Step-Up operation was rejected.");
    }

    @ExceptionHandler(MfaRejectedException.class)
    ResponseEntity<Map<String, Object>> mfaRejected(MfaRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case ASSERTION_REJECTED -> HttpStatus.FORBIDDEN;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        return problems.response(status, "mfa_rejected", "The MFA operation was rejected.");
    }

    @ExceptionHandler(ProcessRejectedException.class)
    ResponseEntity<Map<String, Object>> processRejected(ProcessRejectedException exception) {
        return problems.response(HttpStatus.CONFLICT, "process_rejected", "The process operation was rejected.");
    }

    @ExceptionHandler(SecurityAuditUnavailableException.class)
    ResponseEntity<Map<String, Object>> auditUnavailable(SecurityAuditUnavailableException exception) {
        return problems.response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "security_audit_unavailable",
                "Security audit persistence is unavailable.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalidInput(IllegalArgumentException exception) {
        return problems.response(HttpStatus.BAD_REQUEST, "invalid_request", "The request is invalid.");
    }
}
