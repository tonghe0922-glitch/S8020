package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.RequestAuditContext;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
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

/** Standard RFC 7807-compatible error envelope for P006-P010 REST controllers. */
@RestControllerAdvice(basePackages = "cn.shangjingu.platform.api.phase10")
public class Phase10ApiExceptionHandler {
    @ExceptionHandler(ProcessRejectedException.class)
    public ResponseEntity<Map<String, Object>> processRejected(ProcessRejectedException exception) {
        return problem(
                HttpStatus.CONFLICT,
                "PROCESS_REJECTED",
                detail(exception, "The requested process transition was rejected"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION", detail(exception, "The resource version is stale"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException exception) {
        String detail = detail(exception, "The request argument is invalid");
        boolean notFound = detail.toLowerCase(Locale.ROOT).contains("not found");
        return problem(
                notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                notFound ? "NOT_FOUND" : "INVALID_ARGUMENT",
                detail);
    }

    private static String detail(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? fallback : exception.getMessage();
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
