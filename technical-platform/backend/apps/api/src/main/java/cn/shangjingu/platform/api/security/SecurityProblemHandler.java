package cn.shangjingu.platform.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public final class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;
    private final JdbcSecurityAuditService audit;

    public SecurityProblemHandler(ObjectMapper objectMapper, JdbcSecurityAuditService audit) {
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {
        write(response, 401, "unauthorized", "Authentication is required.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException, ServletException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            try {
                audit.recordSecurityEvent(
                        principal.context().tenantId(),
                        principal.context().userId(),
                        principal.context().identityId(),
                        "AUTHORIZATION_DENIED",
                        "WARN",
                        "DENIED");
            } catch (SecurityAuditUnavailableException ex) {
                write(response, 503, "security_audit_unavailable", "Security audit persistence is unavailable.");
                return;
            }
        }
        write(response, 403, "forbidden", "The current identity is not authorized for this action.");
    }

    private void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        RequestAuditContext requestContext = RequestAuditContext.current();
        body.put("requestId", requestContext == null ? UUID.randomUUID().toString() : requestContext.requestId());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
