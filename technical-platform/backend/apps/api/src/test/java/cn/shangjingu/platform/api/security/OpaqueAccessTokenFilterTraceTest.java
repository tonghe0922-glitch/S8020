package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.session.SessionStoreUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class OpaqueAccessTokenFilterTraceTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesServerTraceForWholeApiChainAndClearsItAfterwards() throws Exception {
        OpaqueAccessTokenFilter filter = filter(mock(SessionService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/platform/webhooks/t/e");
        request.addHeader("X-Request-Id", "request-661");
        request.addHeader("X-Correlation-Id", "correlation-661");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<PlatformTraceContext> observed = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> observed.set(PlatformTraceContextHolder.currentOrNull()));

        assertNotNull(observed.get());
        assertEquals("correlation-661", observed.get().correlationId());
        assertEquals("correlation-661", response.getHeader("X-Correlation-Id"));
        assertEquals(observed.get().traceId(), response.getHeader("X-Trace-Id"));
        assertNull(PlatformTraceContextHolder.currentOrNull(), "trace holder must not leak into the next request");
    }

    @Test
    void returnsExplicitServiceUnavailableWhenRedisCannotAuthenticateToken() throws Exception {
        SessionService sessions = mock(SessionService.class);
        when(sessions.authenticateAccess(anyString()))
                .thenThrow(new SessionStoreUnavailableException(new IllegalStateException("redis down")));
        OpaqueAccessTokenFilter filter = filter(sessions);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader("Authorization", "Bearer test-token");
        request.addHeader("X-Request-Id", "request-redis-down");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("filter chain must not continue when session storage is unavailable");
        });

        assertEquals(503, response.getStatus());
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())));
        assertEquals(
                "session_store_unavailable",
                new ObjectMapper()
                        .readTree(response.getContentAsByteArray())
                        .get("code")
                        .asText());
        assertEquals(
                "request-redis-down",
                new ObjectMapper()
                        .readTree(response.getContentAsByteArray())
                        .get("requestId")
                        .asText());
    }

    private static OpaqueAccessTokenFilter filter(SessionService sessions) {
        return new OpaqueAccessTokenFilter(
                sessions, mock(IdentityDirectoryService.class), new ApiProblemSupport(new ObjectMapper()));
    }
}
