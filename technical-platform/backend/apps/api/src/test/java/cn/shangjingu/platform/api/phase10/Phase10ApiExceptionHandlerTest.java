package cn.shangjingu.platform.api.phase10;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class Phase10ApiExceptionHandlerTest {
    private final Phase10ApiExceptionHandler handler =
            new Phase10ApiExceptionHandler();

    @Test
    void processRejectionUsesConflictProblemEnvelope() {
        ResponseEntity<Map<String, Object>> response =
                handler.processRejected(
                        new ProcessRejectedException("P008 quota transition rejected"));

        assertProblem(response, HttpStatus.CONFLICT, "PROCESS_REJECTED");
    }

    @Test
    void staleVersionUsesConflictProblemEnvelope() {
        ResponseEntity<Map<String, Object>> response =
                handler.optimisticLock(
                        new OptimisticLockingFailureException("stale version"));

        assertProblem(response, HttpStatus.CONFLICT, "STALE_VERSION");
    }

    @Test
    void missingResourceUsesNotFoundProblemEnvelope() {
        ResponseEntity<Map<String, Object>> response =
                handler.invalidArgument(
                        new IllegalArgumentException("P010 assignment not found"));

        assertProblem(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void invalidArgumentUsesBadRequestProblemEnvelope() {
        ResponseEntity<Map<String, Object>> response =
                handler.invalidArgument(
                        new IllegalArgumentException("score must be between 0 and 1000"));

        assertProblem(response, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
    }

    private static void assertProblem(
            ResponseEntity<Map<String, Object>> response,
            HttpStatus status,
            String code) {
        assertEquals(status, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(status.value(), body.get("status"));
        assertEquals(code, body.get("code"));
        assertNotNull(body.get("detail"));
        assertNotNull(body.get("requestId"));
    }
}
