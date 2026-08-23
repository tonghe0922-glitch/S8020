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
        return problems.response(HttpStatus.UNAUTHORIZED, "authentication_rejected", "账号认证失败，请检查公司名称、登录账号、密码或验证码。");
    }

    @ExceptionHandler(SessionRejectedException.class)
    ResponseEntity<Map<String, Object>> sessionRejected(SessionRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case INVALID_ACCESS, INVALID_REFRESH, REFRESH_REPLAY -> HttpStatus.UNAUTHORIZED;
                    case IDENTITY_INACTIVE, APPOINTMENT_INACTIVE -> HttpStatus.FORBIDDEN;
                    case SESSION_CONFLICT -> HttpStatus.CONFLICT;
                };
        return problems.response(status, "session_rejected", "当前会话已失效或被拒绝，请重新登录。");
    }

    @ExceptionHandler(SessionStoreUnavailableException.class)
    ResponseEntity<Map<String, Object>> sessionStoreUnavailable(SessionStoreUnavailableException exception) {
        return problems.response(HttpStatus.SERVICE_UNAVAILABLE, "session_store_unavailable", "会话存储服务暂时不可用，请联系运维人员处理。");
    }

    @ExceptionHandler(StepUpRejectedException.class)
    ResponseEntity<Map<String, Object>> stepUpRejected(StepUpRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case AUDIT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case TICKET_CONFLICT -> HttpStatus.CONFLICT;
                    default -> HttpStatus.FORBIDDEN;
                };
        return problems.response(status, "step_up_rejected", "增强认证操作未通过，请重新验证或联系管理员。");
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
        return problems.response(status, "mfa_rejected", "多因素认证操作未通过，请检查验证码或认证状态。");
    }

    @ExceptionHandler(ProcessRejectedException.class)
    ResponseEntity<Map<String, Object>> processRejected(ProcessRejectedException exception) {
        return problems.response(HttpStatus.CONFLICT, "process_rejected", "流程操作未成功，请刷新后重试或联系管理员。");
    }

    @ExceptionHandler(SecurityAuditUnavailableException.class)
    ResponseEntity<Map<String, Object>> auditUnavailable(SecurityAuditUnavailableException exception) {
        return problems.response(HttpStatus.SERVICE_UNAVAILABLE, "security_audit_unavailable", "安全审计服务暂时不可用，请联系系统管理员。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalidInput(IllegalArgumentException exception) {
        return problems.response(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "请求参数无效：" + PublicProblemDetail.localized(exception, "请检查输入内容。"));
    }
}
