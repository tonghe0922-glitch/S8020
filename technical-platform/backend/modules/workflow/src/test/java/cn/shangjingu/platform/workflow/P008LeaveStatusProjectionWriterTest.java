package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class P008LeaveStatusProjectionWriterTest {
    @Test
    void terminalProjectionDoesNotOverwriteActualReturnFact() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        P008LeaveStatusProjectionWriter writer = new P008LeaveStatusProjectionWriter(jdbc);
        Instant closedAt = Instant.parse("2026-08-14T09:30:00Z");

        int updated = writer.moveStatus(UUID.randomUUID(), UUID.randomUUID(), 8, "已关闭", closedAt, UUID.randomUUID());

        assertEquals(1, updated);
        assertTrue(jdbc.sql.contains("closed_at=coalesce(?,closed_at)"));
        assertFalse(jdbc.sql.contains("actual_end_at"));
        assertEquals(6, jdbc.arguments.length);
        assertEquals(closedAt, ((java.sql.Timestamp) jdbc.arguments[1]).toInstant());
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] arguments;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return 1;
        }
    }
}
