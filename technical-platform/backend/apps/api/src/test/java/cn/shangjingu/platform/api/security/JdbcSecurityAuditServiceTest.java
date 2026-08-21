package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JdbcSecurityAuditServiceTest {
    private static final String UNAVAILABLE_URL =
            "jdbc:postgresql://127.0.0.1:1/sjg_audit?connectTimeout=1";

    @AfterEach
    void clearRequestContext() {
        RequestAuditContext.clear();
    }

    @Test
    void failOpenLogsAndCountsTheFailureWithoutBlockingLoginEvents() {
        JdbcSecurityAuditService audit = new JdbcSecurityAuditService(
                UNAVAILABLE_URL,
                "sjg_audit_writer",
                "not-used",
                SecurityAuditMode.FAIL_OPEN);
        RequestAuditContext.install(new RequestAuditContext(
                "request-audit-open",
                "127.0.0.1",
                "test-device"));

        assertDoesNotThrow(() -> audit.recordSecurityEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "LOGIN_REJECTED",
                "WARN",
                "INVALID_CREDENTIALS"));
        assertEquals(1, audit.failedWriteCount());
    }

    @Test
    void failClosedPreservesTheComplianceGate() {
        JdbcSecurityAuditService audit = new JdbcSecurityAuditService(
                UNAVAILABLE_URL,
                "sjg_audit_writer",
                "not-used",
                SecurityAuditMode.FAIL_CLOSED);

        assertThrows(SecurityAuditUnavailableException.class, () -> audit.recordSecurityEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "LOGIN_REJECTED",
                "WARN",
                "INVALID_CREDENTIALS"));
        assertEquals(1, audit.failedWriteCount());
    }
}
