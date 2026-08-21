package cn.shangjingu.platform.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.shangjingu.platform.iam.session.SessionRejectedException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiSecurityExceptionHandlerTest {
    private final ApiSecurityExceptionHandler handler = new ApiSecurityExceptionHandler();

    @Test
    void distinguishesRedisSessionStorageFromCredentialRejection() {
        ResponseEntity<Map<String, Object>> response = handler.sessionRejected(
                new SessionRejectedException(
                        SessionRejectedException.Reason.STORE_UNAVAILABLE,
                        new IllegalStateException("redis down")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("code", "session_store_unavailable")
                .containsKey("requestId");
    }
}
