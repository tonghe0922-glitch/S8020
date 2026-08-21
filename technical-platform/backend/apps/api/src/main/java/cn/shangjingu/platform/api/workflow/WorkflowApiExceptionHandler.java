package cn.shangjingu.platform.api.workflow;

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
public final class WorkflowApiExceptionHandler {
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> workflow(WorkflowException ex) {
        HttpStatus status = switch (ex.code()) {
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
        body.put("code", ex.code().name());
        body.put("detail", ex.getMessage() == null ? ex.code().name() : ex.getMessage());
        RequestAuditContext request = RequestAuditContext.current();
        body.put("requestId", request == null ? UUID.randomUUID().toString() : request.requestId());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
