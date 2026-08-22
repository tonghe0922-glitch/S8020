package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists the P007 canonical shift record with semantic named parameters. */
@Repository
public class P007ShiftChangeInsertWriter {
    static final String INSERT_SQL =
            """
            insert into attendance.shift_change_request(
              id,tenant_id,business_no,status,version_no,
              created_by,updated_by,source_channel,business_date,
              subject,reason,priority,owner_center_id,owner_employee_id,
              attendance_type,change_action,change_reason,content_version,
              duration_hours,end_at,period_or_course_no,start_at,
              template_code,target_employee_id,replacement_employee_id
            )
            values(
              :id,:tenantId,:businessNo,:status,0,
              :actor,:actor,'PC',current_date,
              :subject,:reason,'NORMAL',:ownerCenterId,:ownerEmployeeId,
              '排班',:changeAction,:changeReason,:contentVersion,
              :durationHours,:endAt,:periodOrCourseNo,:startAt,
              :templateCode,:targetEmployeeId,:replacementEmployeeId
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public P007ShiftChangeInsertWriter(JdbcTemplate jdbc) {
        this(new NamedParameterJdbcTemplate(jdbc));
    }

    P007ShiftChangeInsertWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ShiftChangeService.ShiftRecord record, UUID actor) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("tenantId", record.tenantId())
                .addValue("businessNo", record.businessNo())
                .addValue("status", record.status())
                .addValue("actor", actor)
                .addValue("subject", record.subject())
                .addValue("reason", record.reason())
                .addValue("ownerCenterId", record.ownerCenterId())
                .addValue("ownerEmployeeId", record.ownerEmployeeId())
                .addValue("changeAction", record.changeAction())
                .addValue("changeReason", record.changeReason())
                .addValue("contentVersion", record.templateCode() == null ? "CURRENT" : record.templateCode())
                .addValue("durationHours", record.durationHours())
                .addValue("endAt", timestamp(record.endAt()))
                .addValue("periodOrCourseNo", record.periodOrCourseNo())
                .addValue("startAt", timestamp(record.startAt()))
                .addValue("templateCode", record.templateCode())
                .addValue("targetEmployeeId", record.targetEmployeeId())
                .addValue("replacementEmployeeId", record.replacementEmployeeId());
        int inserted = jdbc.update(INSERT_SQL, parameters);
        if (inserted != 1) {
            throw new ProcessRejectedException("P007 canonical shift record insert failed");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
