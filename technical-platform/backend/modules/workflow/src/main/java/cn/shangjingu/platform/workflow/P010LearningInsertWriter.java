package cn.shangjingu.platform.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists a P010 assignment with semantic named parameters. */
@Repository
public class P010LearningInsertWriter {
    static final String INSERT_SQL = """
            insert into learning.learning_assignment(
              id,tenant_id,business_no,status,version_no,created_by,updated_by,
              source_channel,business_date,subject,reason,priority,risk_level,
              owner_center_id,owner_employee_id,planned_start_at,planned_finish_at,
              completion_rate,content_version,course_team_name,course_version_id,
              learner_profile,period_or_course_no,phase_node_code)
            values(
              :id,:tenantId,:businessNo,:status,0,:actor,:actor,
              'PORTAL',current_date,:subject,:reason,'NORMAL',:riskLevel,
              :ownerCenterId,:ownerEmployeeId,:plannedStartAt,:plannedFinishAt,
              0,:contentVersion,:courseTeamName,:courseVersionId,
              :learnerProfile,:periodOrCourseNo,'S01')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public P010LearningInsertWriter(JdbcTemplate jdbc) {
        this(new NamedParameterJdbcTemplate(jdbc));
    }

    P010LearningInsertWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            LearningService.LearningRecord record,
            String reason,
            String courseTeamName,
            String riskLevel,
            String learnerProfile,
            Instant plannedStartAt,
            Instant plannedFinishAt,
            UUID actor) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("id", record.id())
                        .addValue("tenantId", record.tenantId())
                        .addValue("businessNo", record.businessNo())
                        .addValue("status", record.status())
                        .addValue("actor", actor)
                        .addValue("subject", record.subject())
                        .addValue("reason", reason)
                        .addValue("riskLevel", riskLevel)
                        .addValue("ownerCenterId", record.ownerCenterId())
                        .addValue("ownerEmployeeId", record.ownerEmployeeId())
                        .addValue("plannedStartAt", timestamp(plannedStartAt))
                        .addValue("plannedFinishAt", timestamp(plannedFinishAt))
                        .addValue("contentVersion", record.contentVersion())
                        .addValue("courseTeamName", courseTeamName)
                        .addValue("courseVersionId", record.courseVersionId())
                        .addValue("learnerProfile", learnerProfile)
                        .addValue("periodOrCourseNo", record.periodOrCourseNo());
        jdbc.update(INSERT_SQL, parameters);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
