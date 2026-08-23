package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.PublicProblemDetail;
import cn.shangjingu.platform.api.security.RequestAuditContext;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cn.shangjingu.platform.api.phase11")
public class Phase11ApiExceptionHandler {
    @ExceptionHandler(ProcessRejectedException.class)
    public ResponseEntity<Map<String, Object>> processRejected(ProcessRejectedException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "PROCESS_REJECTED",
                PublicProblemDetail.localized(exception, "当前流程状态不允许执行此操作，请刷新后重试。"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                PublicProblemDetail.localized(exception, "数据版本已发生变化，请刷新后重试。"));
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> workflow(WorkflowException exception) {
        HttpStatus status =
                switch (exception.code()) {
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case STALE_VERSION, CONFLICT, ILLEGAL_ACTION -> HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return problem(
                status,
                "WORKFLOW_" + exception.code().name(),
                PublicProblemDetail.localized(exception, workflowFallback(exception)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException exception) {
        String detail = PublicProblemDetail.localized(exception, "请求参数无效，请检查输入内容。");
        boolean notFound = PublicProblemDetail.isNotFound(exception);
        return problem(
                notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                notFound ? "NOT_FOUND" : "INVALID_ARGUMENT",
                detail);
    }

    private static String workflowFallback(WorkflowException exception) {
        return switch (exception.code()) {
            case FORBIDDEN -> "当前身份暂无执行此流程操作的权限。";
            case NOT_FOUND -> "未找到相关流程数据。";
            case STALE_VERSION -> "流程数据版本已发生变化，请刷新后重试。";
            case CONFLICT, ILLEGAL_ACTION -> "当前流程状态不允许执行此操作。";
            default -> "流程请求未成功，请检查输入后重试。";
        };
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        RequestAuditContext context = RequestAuditContext.current();
        body.put("requestId", context == null ? UUID.randomUUID().toString() : context.requestId());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
