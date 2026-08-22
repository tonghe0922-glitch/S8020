package cn.shangjingu.platform.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityAuditModeTest {
    private static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1";

    @Test
    void parsesOnlyTheTwoDocumentedModes() {
        assertEquals(SecurityAuditMode.FAIL_OPEN, SecurityAuditMode.parse(null));
        assertEquals(SecurityAuditMode.FAIL_OPEN, SecurityAuditMode.parse("fail_open"));
        assertEquals(SecurityAuditMode.FAIL_CLOSED, SecurityAuditMode.parse("FAIL-CLOSED"));
        assertThrows(IllegalArgumentException.class, () -> SecurityAuditMode.parse("best-effort"));
    }

    @Test
    void failOpenLogsAndCountsAuditFailureWithoutBlockingCaller() {
        JdbcSecurityAuditService audit =
                new JdbcSecurityAuditService(UNREACHABLE_URL, "audit", "unused", SecurityAuditMode.FAIL_OPEN);

        audit.recordSecurityEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "LOGIN_REJECTED",
                "WARN",
                "INVALID_CREDENTIALS");

        assertEquals(1, audit.writeFailureCount());
        assertTrue(audit.lastFailureAt() != null);
        assertFalse(audit.isAvailable());
    }

    @Test
    void failClosedReturnsStableAuditUnavailableException() {
        JdbcSecurityAuditService audit =
                new JdbcSecurityAuditService(UNREACHABLE_URL, "audit", "unused", SecurityAuditMode.FAIL_CLOSED);

        assertThrows(
                SecurityAuditUnavailableException.class,
                () -> audit.recordSecurityEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "LOGIN_REJECTED",
                        "WARN",
                        "INVALID_CREDENTIALS"));
        assertEquals(1, audit.writeFailureCount());
    }
}
