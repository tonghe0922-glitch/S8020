package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

@Component
public final class WorkerCriticalAuditService {
    private final JdbcTemplate auditJdbc;

    public WorkerCriticalAuditService(
            @Value("${sjg.audit.datasource.url:jdbc:postgresql://localhost:5432/sjg_audit}") String url,
            @Value("${sjg.audit.datasource.username:sjg_audit_writer}") String username,
            @Value("${sjg.audit.datasource.password:}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        this.auditJdbc = new JdbcTemplate(dataSource);
    }

    public void record(UUID tenantId, String action, String resourceType, UUID resourceId, Map<String, ?> details) {
        if (tenantId == null || blank(action) || blank(resourceType)) {
            throw new ProcessRejectedException("critical worker audit context is incomplete");
        }
        try {
            auditJdbc.update("""
                    insert into audit.operation_log(
                        tenant_id,action,resource_type,resource_id,request_id)
                    values (?,?,?,?,?)
                    """, tenantId, action, resourceType, resourceId, requestId(details));
        } catch (DataAccessException ex) {
            throw new ProcessRejectedException("critical worker audit persistence is unavailable", ex);
        }
    }

    private static String requestId(Map<String, ?> details) {
        if (details != null) {
            Object eventId = details.get("event_id");
            if (eventId != null) {
                try {
                    return "worker-" + UUID.fromString(eventId.toString());
                } catch (IllegalArgumentException ignored) {
                    // Only canonical UUID event identifiers are accepted as the cross-database trace key.
                }
            }
        }
        return "worker-" + UUID.randomUUID();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
