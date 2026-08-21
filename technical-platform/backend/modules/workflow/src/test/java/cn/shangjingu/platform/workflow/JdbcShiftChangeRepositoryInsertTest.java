package cn.shangjingu.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcShiftChangeRepositoryInsertTest {
    @Test
    void productionInsertWriterUsesNamedParametersAndPreservesFieldSemantics() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(
                        eq(P007ShiftChangeInsertWriter.INSERT_SQL),
                        any(SqlParameterSource.class)))
                .thenReturn(1);
        P007ShiftChangeInsertWriter writer = new P007ShiftChangeInsertWriter(jdbc);
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID ownerCenterId = UUID.randomUUID();
        UUID ownerEmployeeId = UUID.randomUUID();
        UUID targetEmployeeId = UUID.randomUUID();
        UUID replacementEmployeeId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ShiftChangeService.ShiftRecord record =
                new ShiftChangeService.ShiftRecord(
                        id,
                        tenantId,
                        "P007-TEST-001",
                        null,
                        null,
                        "S01",
                        "草稿",
                        0,
                        "P007 test shift",
                        "test reason",
                        ownerCenterId,
                        ownerEmployeeId,
                        targetEmployeeId,
                        replacementEmployeeId,
                        "SHIFT_CHANGE",
                        "test change",
                        "TPL-A",
                        "2031-05",
                        Instant.parse("2031-05-01T01:00:00Z"),
                        Instant.parse("2031-05-01T09:00:00Z"),
                        BigDecimal.valueOf(8),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now());

        writer.insert(record, actor);

        ArgumentCaptor<SqlParameterSource> captor =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc)
                .update(eq(P007ShiftChangeInsertWriter.INSERT_SQL), captor.capture());
        MapSqlParameterSource parameters = (MapSqlParameterSource) captor.getValue();
        assertFalse(P007ShiftChangeInsertWriter.INSERT_SQL.contains("?"));
        assertEquals(
                Set.of(
                        "id", "tenantId", "businessNo", "status", "actor", "subject",
                        "reason", "ownerCenterId", "ownerEmployeeId", "changeAction",
                        "changeReason", "contentVersion", "durationHours", "endAt",
                        "periodOrCourseNo", "startAt", "templateCode",
                        "targetEmployeeId", "replacementEmployeeId"),
                Set.of(parameters.getParameterNames()));
        assertEquals(id, parameters.getValue("id"));
        assertEquals(tenantId, parameters.getValue("tenantId"));
        assertEquals(ownerCenterId, parameters.getValue("ownerCenterId"));
        assertEquals(ownerEmployeeId, parameters.getValue("ownerEmployeeId"));
        assertEquals(targetEmployeeId, parameters.getValue("targetEmployeeId"));
        assertEquals(replacementEmployeeId, parameters.getValue("replacementEmployeeId"));
        assertEquals(actor, parameters.getValue("actor"));
        assertEquals("TPL-A", parameters.getValue("contentVersion"));
    }
}
