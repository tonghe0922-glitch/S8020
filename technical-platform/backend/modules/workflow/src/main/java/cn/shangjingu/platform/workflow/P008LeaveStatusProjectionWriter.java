package cn.shangjingu.platform.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists P008 workflow closure without overwriting the independently recorded return fact. */
@Repository
public class P008LeaveStatusProjectionWriter {
    private final JdbcTemplate jdbc;

    public P008LeaveStatusProjectionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int moveStatus(
            UUID tenantId,
            UUID id,
            int version,
            String status,
            Instant closedAt,
            UUID actor) {
        return jdbc.update(
                """
                update attendance.leave_request
                set status=?,
                    closed_at=coalesce(?,closed_at),
                    version_no=version_no+1,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and version_no=?
                  and not is_deleted
                """,
                status,
                timestamp(closedAt),
                actor,
                tenantId,
                id,
                version);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
