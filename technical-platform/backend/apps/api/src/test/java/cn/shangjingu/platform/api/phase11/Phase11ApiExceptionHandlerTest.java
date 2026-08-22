package cn.shangjingu.platform.api.phase11;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.WorkflowException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class Phase11ApiExceptionHandlerTest {
    private final Phase11ApiExceptionHandler handler = new Phase11ApiExceptionHandler();

    @Test
    void processRejectionUsesProblemJsonAndConflict() {
        ResponseEntity<Map<String, Object>> response =
                handler.processRejected(new ProcessRejectedException("P011 version conflict"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals("PROCESS_REJECTED", response.getBody().get("code"));
    }

    @Test
    void workflowNotFoundIs404() {
        ResponseEntity<Map<String, Object>> response =
                handler.workflow(new WorkflowException(WorkflowException.Code.NOT_FOUND, "task not found"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("WORKFLOW_NOT_FOUND", response.getBody().get("code"));
    }

    @Test
    void missingAggregateIs404AndBadInputIs400() {
        assertEquals(
                HttpStatus.NOT_FOUND,
                handler.invalidArgument(new IllegalArgumentException("P011 performance cycle not found"))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                handler.invalidArgument(new IllegalArgumentException("score type is invalid"))
                        .getStatusCode());
    }
}
