package cn.shangjingu.platform.api.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ApiProblemSupport problems;
    private final JdbcSecurityAuditService audit;

    public SecurityProblemHandler(ApiProblemSupport problems, JdbcSecurityAuditService audit) {
        this.problems = problems;
        this.audit = audit;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authenticationException)
            throws IOException, ServletException {
        problems.write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            try {
                audit.recordSecurityEvent(
                        principal.context().tenantId(),
                        principal.context().userId(),
                        principal.context().identityId(),
                        "AUTHORIZATION_DENIED",
                        "WARN",
                        "DENIED");
            } catch (SecurityAuditUnavailableException exception) {
                problems.write(
                        response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "security_audit_unavailable",
                        "Security audit persistence is unavailable.");
                return;
            }
        }
        problems.write(
                response, HttpStatus.FORBIDDEN, "forbidden", "The current identity is not authorized for this action.");
    }
}
