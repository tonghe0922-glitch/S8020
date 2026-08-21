package cn.shangjingu.platform.core.database;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class TenantTransactionRunner {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TenantTransactionRunner(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T required(UUID tenantId, Supplier<T> work) {
        return required(DatabaseSecurityContext.tenantOnly(tenantId), work);
    }

    public <T> T required(UUID tenantId, UUID userId, UUID identityId, Supplier<T> work) {
        return required(new DatabaseSecurityContext(tenantId, userId, identityId, null, null, null, null), work);
    }

    public <T> T required(DatabaseSecurityContext context, Supplier<T> work) {
        return transactionTemplate.execute(status -> {
            setLocal("app.tenant_id", context.tenantId());
            setLocal("app.user_id", context.userId());
            setLocal("app.identity_id", context.identityId());
            setLocal("app.employee_id", context.employeeId());
            setLocal("app.appointment_id", context.appointmentId());
            setLocal("app.org_id", context.orgId());
            setLocal("app.position_id", context.positionId());
            return work.get();
        });
    }

    private void setLocal(String key, UUID value) {
        if (value != null) {
            jdbcTemplate.queryForObject("select set_config(?, ?, true)", String.class, key, value.toString());
        }
    }
}
