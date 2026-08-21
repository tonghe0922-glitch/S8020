package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.SessionService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class OpaqueAccessTokenFilterTraceTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test
    void establishesServerTraceForWholeApiChainAndClearsItAfterwards() throws Exception {
        OpaqueAccessTokenFilter filter = new OpaqueAccessTokenFilter(mock(SessionService.class), mock(IdentityDirectoryService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST","/api/v1/platform/webhooks/t/e");
        request.addHeader("X-Request-Id","request-661"); request.addHeader("X-Correlation-Id","correlation-661");
        MockHttpServletResponse response = new MockHttpServletResponse(); AtomicReference<PlatformTraceContext> observed = new AtomicReference<>();
        filter.doFilter(request,response,(req,res)->observed.set(PlatformTraceContextHolder.currentOrNull()));
        assertNotNull(observed.get()); assertEquals("correlation-661",observed.get().correlationId());
        assertEquals("correlation-661",response.getHeader("X-Correlation-Id")); assertEquals(observed.get().traceId(),response.getHeader("X-Trace-Id"));
        assertNull(PlatformTraceContextHolder.currentOrNull(),"trace holder must not leak into the next request");
    }
}
