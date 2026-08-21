package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.RequestAuditContext;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cn.shangjingu.platform.api.phase11")
public final class Phase11ApiExceptionHandler {
    @ExceptionHandler(ProcessRejectedException.class)
    public ResponseEntity<Map<String, Object>> processRejected(
            ProcessRejectedException exception) {
        return problem(HttpStatus.CONFLICT, "PROCESS_REJECTED", detail(exception));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(
            OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION", detail(exception));
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<Map<String, Object>> workflow(WorkflowException exception) {
        HttpStatus status = switch (exception.code()) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STALE_VERSION, CONFLICT, ILLEGAL_ACTION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(
                status,
                "WORKFLOW_" + exception.code().name(),
                detail(exception));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidArgument(
            IllegalArgumentException exception) {
        String detail = detail(exception);
        boolean notFound = detail.toLowerCase(Locale.ROOT).contains("not found");
        return problem(
                notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                notFound ? "NOT_FOUND" : "INVALID_ARGUMENT",
                detail);
    }

    private static String detail(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "PHASE-11 request failed"
                : exception.getMessage();
    }

    private static ResponseEntity<Map<String, Object>> problem(
            HttpStatus status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        RequestAuditContext context = RequestAuditContext.current();
        body.put(
                "requestId",
                context == null ? UUID.randomUUID().toString() : context.requestId());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
