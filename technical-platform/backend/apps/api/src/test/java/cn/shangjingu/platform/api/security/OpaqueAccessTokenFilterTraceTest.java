package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.SessionRejectedException;
import cn.shangjingu.platform.iam.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class OpaqueAccessTokenFilterTraceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesServerTraceForWholeApiChainAndClearsItAfterwards() throws Exception {
        OpaqueAccessTokenFilter filter = new OpaqueAccessTokenFilter(
                mock(SessionService.class),
                mock(IdentityDirectoryService.class),
                objectMapper);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/platform/webhooks/t/e");
        request.addHeader("X-Request-Id", "request-661");
        request.addHeader("X-Correlation-Id", "correlation-661");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<PlatformTraceContext> observed = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        observed.set(PlatformTraceContextHolder.currentOrNull()));

        assertNotNull(observed.get());
        assertEquals("correlation-661", observed.get().correlationId());
        assertEquals("correlation-661", response.getHeader("X-Correlation-Id"));
        assertEquals(observed.get().traceId(), response.getHeader("X-Trace-Id"));
        assertNull(
                PlatformTraceContextHolder.currentOrNull(),
                "trace holder must not leak into the next request");
    }

    @Test
    void mapsRedisFailureBeforeControllersToAServiceUnavailableProblem() throws Exception {
        SessionService sessions = mock(SessionService.class);
        when(sessions.authenticateAccess(anyString())).thenThrow(new SessionRejectedException(
                SessionRejectedException.Reason.STORE_UNAVAILABLE,
                new IllegalStateException("redis down")));
        OpaqueAccessTokenFilter filter = new OpaqueAccessTokenFilter(
                sessions,
                mock(IdentityDirectoryService.class),
                objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader("Authorization", "Bearer opaque-token");
        request.addHeader("X-Request-Id", "request-redis-down");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("filter chain must not run while Redis is unavailable");
        });

        assertEquals(503, response.getStatus());
        JsonNode problem = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals("session_store_unavailable", problem.path("code").asText());
        assertEquals("request-redis-down", problem.path("requestId").asText());
    }
}
