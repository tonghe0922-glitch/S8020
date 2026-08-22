package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.session.SessionStoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OpaqueAccessTokenFilter extends OncePerRequestFilter {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final SessionService sessions;
    private final IdentityDirectoryService identities;
    private final ApiProblemSupport problems;

    public OpaqueAccessTokenFilter(
            SessionService sessions, IdentityDirectoryService identities, ApiProblemSupport problems) {
        this.sessions = sessions;
        this.identities = identities;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = normalizedRequestId(request.getHeader("X-Request-Id"));
        String correlationId = normalizedCorrelationId(request.getHeader("X-Correlation-Id"), requestId);
        PlatformTraceContext trace =
                new PlatformTraceContext(correlationId, UUID.randomUUID().toString());
        RequestAuditContext.install(new RequestAuditContext(
                requestId, request.getRemoteAddr(), bounded(request.getHeader("X-Device-Fingerprint"), 255)));
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", trace.correlationId());
        response.setHeader("X-Trace-Id", trace.traceId());

        try (PlatformTraceContextHolder.Scope ignored = PlatformTraceContextHolder.open(trace)) {
            try {
                authenticate(request);
            } catch (SessionStoreUnavailableException exception) {
                SecurityContextHolder.clearContext();
                problems.write(
                        response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "session_store_unavailable",
                        "Session storage is unavailable.");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestAuditContext.clear();
        }
    }

    private void authenticate(HttpServletRequest request) {
        Optional<String> bearer = bearerToken(request);
        bearer.flatMap(sessions::authenticateAccess).ifPresent(subject -> {
            List<SimpleGrantedAuthority> authorities = identities.authorization(subject).grants().stream()
                    .map(grant -> grant.permissionCode())
                    .distinct()
                    .sorted()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            SessionPrincipal principal = new SessionPrincipal(bearer.orElseThrow(), subject);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        });
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring(7).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static String normalizedRequestId(String candidate) {
        return candidate != null && REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }

    private static String normalizedCorrelationId(String candidate, String requestId) {
        return candidate != null && CORRELATION_ID.matcher(candidate).matches() ? candidate : requestId;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
