package cn.shangjingu.platform.api.workflow;

import cn.shangjingu.platform.api.security.PublicProblemDetail;
import cn.shangjingu.platform.api.security.RequestAuditContext;
import cn.shangjingu.platform.workflow.WorkflowException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WorkflowApiExceptionHandler {
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> workflow(WorkflowException exception) {
        HttpStatus status =
                switch (exception.code()) {
                    case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case NO_ELIGIBLE_APPROVER,
                            CONFLICT,
                            INVALID_DEFINITION,
                            IMMUTABLE_PUBLISHED_VERSION,
                            ILLEGAL_ACTION,
                            STALE_VERSION -> HttpStatus.CONFLICT;
                };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", exception.code().name());
        body.put("detail", PublicProblemDetail.localized(exception, fallback(exception)));
        RequestAuditContext request = RequestAuditContext.current();
        body.put("requestId", request == null ? UUID.randomUUID().toString() : request.requestId());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String fallback(WorkflowException exception) {
        return switch (exception.code()) {
            case INVALID_ARGUMENT -> "流程请求参数无效，请检查输入内容。";
            case NOT_FOUND -> "未找到相关流程数据。";
            case FORBIDDEN -> "当前身份暂无执行此流程操作的权限。";
            case NO_ELIGIBLE_APPROVER -> "当前流程未配置可用审批人，请联系管理员。";
            case CONFLICT,
                    INVALID_DEFINITION,
                    IMMUTABLE_PUBLISHED_VERSION,
                    ILLEGAL_ACTION,
                    STALE_VERSION -> "当前流程状态或版本不允许执行此操作，请刷新后重试。";
        };
    }
}
