package cn.shangjingu.platform.api.org;

import cn.shangjingu.platform.api.security.ApiProblemSupport;
import cn.shangjingu.platform.api.security.PublicProblemDetail;
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
        return problems.response(
                HttpStatus.CONFLICT,
                "ORG_ARCHITECTURE_STALE_VERSION",
                PublicProblemDetail.localized(exception, "组织架构数据已发生变化，请刷新后重试。"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception) {
        return problems.response(HttpStatus.CONFLICT, "ORG_ARCHITECTURE_CONFLICT", "组织架构数据存在关联或唯一性冲突，请检查后重试。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        String detail = PublicProblemDetail.localized(exception, "组织架构请求参数无效，请检查输入内容。");
        boolean notFound = PublicProblemDetail.isNotFound(exception);
        return problems.response(
                notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                notFound ? "ORG_ARCHITECTURE_NOT_FOUND" : "ORG_ARCHITECTURE_INVALID",
                detail);
    }
}
