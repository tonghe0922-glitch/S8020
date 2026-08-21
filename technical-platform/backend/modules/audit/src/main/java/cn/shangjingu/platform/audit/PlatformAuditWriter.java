package cn.shangjingu.platform.audit;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reusable append-only writer for platform audit facts.
 * Critical methods deliberately propagate database failures; callers must not continue a critical action
 * when its mandatory audit evidence cannot be appended.
 */
public final class PlatformAuditWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate required;

    public PlatformAuditWriter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        if (jdbc == null || transactionManager == null) throw new IllegalArgumentException("audit writer dependencies are required");
        this.jdbc = jdbc;
        this.required = new TransactionTemplate(transactionManager);
    }

    public UUID appendCriticalOperation(OperationCommand command) {
        validateOperation(command);
        UUID result = required.execute(status -> insertOperation(command));
        if (result == null) throw new IllegalStateException("critical audit transaction returned no evidence id");
        return result;
    }

    /** Explicitly noncritical mode only. Never use this method for security, export, sensitive access or state transition gates. */
    public boolean tryAppendOperation(OperationCommand command) {
        try {
            appendCriticalOperation(command);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public UUID appendCriticalAccess(AccessCommand command) {
        validateAccess(command);
        UUID result = required.execute(status -> {
            setTenant(command.tenantId());
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update("""
                    insert into audit.access_log(
                        id,tenant_id,actor_id,resource_type,resource_id,fields_accessed,purpose,occurred_at,
                        correlation_id,trace_id)
                    values (?,?,?,?,?,cast(? as jsonb),?,now(),?,?)
                    """, id, command.tenantId(), command.actorId(), command.resourceType().trim(), command.resourceId(),
                    command.fieldsAccessedJson(), command.purpose(), correlation(trace), traceId(trace));
            if (inserted != 1) throw new IllegalStateException("critical access audit insert failed");
            return id;
        });
        if (result == null) throw new IllegalStateException("critical access audit transaction returned no evidence id");
        return result;
    }

    public UUID appendSecurityEvent(SecurityEventCommand command) {
        validateSecurity(command);
        UUID result = required.execute(status -> {
            setTenant(command.tenantId());
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update("""
                    insert into audit.security_event(
                        id,tenant_id,event_type,severity,actor_id,ip_address,device_fingerprint,detail,occurred_at,
                        correlation_id,trace_id)
                    values (?,?,?,?,?,cast(? as inet),?,cast(? as jsonb),now(),?,?)
                    """, id, command.tenantId(), command.eventType().trim(), command.severity().trim(), command.actorId(),
                    command.ipAddress(), command.deviceFingerprint(), command.detailJson(), correlation(trace), traceId(trace));
            if (inserted != 1) throw new IllegalStateException("security audit insert failed");
            return id;
        });
        if (result == null) throw new IllegalStateException("security audit transaction returned no evidence id");
        return result;
    }

    private UUID insertOperation(OperationCommand command) {
        setTenant(command.tenantId());
        PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into audit.operation_log(
                    id,tenant_id,actor_id,actor_identity_id,action,resource_type,resource_id,request_id,occurred_at,
                    correlation_id,trace_id)
                values (?,?,?,?,?,?,?,?,now(),?,?)
                """, id, command.tenantId(), command.actorId(), command.actorIdentityId(), command.action().trim(),
                command.resourceType().trim(), command.resourceId(), trimToNull(command.requestId()), correlation(trace), traceId(trace));
        if (inserted != 1) throw new IllegalStateException("critical operation audit insert failed");
        return id;
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String correlation(PlatformTraceContext trace) { return trace == null ? null : trace.correlationId(); }
    private static String traceId(PlatformTraceContext trace) { return trace == null ? null : trace.traceId(); }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static void validateOperation(OperationCommand c) {
        if (c == null || c.tenantId() == null) throw new IllegalArgumentException("audit operation tenant is required");
        text(c.action(),64,"action"); text(c.resourceType(),128,"resourceType"); optional(c.requestId(),128,"requestId");
    }
    private static void validateAccess(AccessCommand c) {
        if (c == null || c.tenantId() == null) throw new IllegalArgumentException("audit access tenant is required");
        text(c.resourceType(),128,"resourceType"); text(c.fieldsAccessedJson(),Integer.MAX_VALUE,"fieldsAccessedJson"); optional(c.purpose(),255,"purpose");
    }
    private static void validateSecurity(SecurityEventCommand c) {
        if (c == null || c.tenantId() == null) throw new IllegalArgumentException("security audit tenant is required");
        text(c.eventType(),64,"eventType"); text(c.severity(),16,"severity"); optional(c.ipAddress(),64,"ipAddress"); optional(c.deviceFingerprint(),255,"deviceFingerprint"); text(c.detailJson(),Integer.MAX_VALUE,"detailJson");
    }
    private static void text(String value,int max,String field){ if(value==null||value.isBlank()||value.length()>max) throw new IllegalArgumentException(field+" is invalid"); }
    private static void optional(String value,int max,String field){ if(value!=null&&value.length()>max) throw new IllegalArgumentException(field+" exceeds "+max+" characters"); }

    public record OperationCommand(UUID tenantId, UUID actorId, UUID actorIdentityId, String action,
                                   String resourceType, UUID resourceId, String requestId) {}
    public record AccessCommand(UUID tenantId, UUID actorId, String resourceType, UUID resourceId,
                                String fieldsAccessedJson, String purpose) {}
    public record SecurityEventCommand(UUID tenantId, String eventType, String severity, UUID actorId,
                                       String ipAddress, String deviceFingerprint, String detailJson) {}
}
