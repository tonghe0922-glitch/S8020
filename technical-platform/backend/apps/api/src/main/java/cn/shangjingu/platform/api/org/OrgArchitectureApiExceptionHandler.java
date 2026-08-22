package cn.shangjingu.platform.api.org;

import cn.shangjingu.platform.api.security.ApiProblemSupport;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cn.shangjingu.platform.api.org")
public class OrgArchitectureApiExceptionHandler {
    private final ApiProblemSupport problems;

    public OrgArchitectureApiExceptionHandler(ApiProblemSupport problems) {
        this.problems = problems;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> stale(OptimisticLockingFailureException exception) {
        return problems.response(HttpStatus.CONFLICT, "ORG_ARCHITECTURE_STALE_VERSION", detail(exception));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception) {
        return problems.response(HttpStatus.CONFLICT, "ORG_ARCHITECTURE_CONFLICT", detail(exception));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        String detail = detail(exception);
        boolean notFound = detail.toLowerCase(Locale.ROOT).contains("not found");
        return problems.response(
                notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                notFound ? "ORG_ARCHITECTURE_NOT_FOUND" : "ORG_ARCHITECTURE_INVALID",
                detail);
    }

    private static String detail(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "organization architecture request failed"
                : exception.getMessage();
    }
}
