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

class P010LearningInsertWriterTest {
    @Test
    void insertUsesSemanticNamedParameters() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(eq(P010LearningInsertWriter.INSERT_SQL), any(SqlParameterSource.class)))
                .thenReturn(1);
        P010LearningInsertWriter writer = new P010LearningInsertWriter(jdbc);
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID ownerCenterId = UUID.randomUUID();
        UUID ownerEmployeeId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LearningService.LearningRecord record = new LearningService.LearningRecord(
                id,
                tenantId,
                "P010-TEST-001",
                null,
                null,
                "S01",
                LearningService.label("S01"),
                0,
                "P010 test assignment",
                ownerCenterId,
                ownerEmployeeId,
                "V1",
                "COURSE-P010-TEST",
                "P010-TEST-PERIOD",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());

        writer.insert(
                record,
                "test reason",
                "test course team",
                "HIGH",
                "test learner profile",
                Instant.parse("2031-05-01T01:00:00Z"),
                Instant.parse("2031-05-31T09:00:00Z"),
                actor);

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(P010LearningInsertWriter.INSERT_SQL), captor.capture());
        MapSqlParameterSource parameters = (MapSqlParameterSource) captor.getValue();
        assertFalse(P010LearningInsertWriter.INSERT_SQL.contains("?"));
        assertEquals(
                Set.of(
                        "id",
                        "tenantId",
                        "businessNo",
                        "status",
                        "actor",
                        "subject",
                        "reason",
                        "riskLevel",
                        "ownerCenterId",
                        "ownerEmployeeId",
                        "plannedStartAt",
                        "plannedFinishAt",
                        "contentVersion",
                        "courseTeamName",
                        "courseVersionId",
                        "learnerProfile",
                        "periodOrCourseNo"),
                Set.of(parameters.getParameterNames()));
        assertEquals(id, parameters.getValue("id"));
        assertEquals(tenantId, parameters.getValue("tenantId"));
        assertEquals(ownerCenterId, parameters.getValue("ownerCenterId"));
        assertEquals(ownerEmployeeId, parameters.getValue("ownerEmployeeId"));
        assertEquals(actor, parameters.getValue("actor"));
        assertEquals("COURSE-P010-TEST", parameters.getValue("courseVersionId"));
    }
}
