package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.iam.session.SessionContext;
import cn.shangjingu.platform.iam.stepup.StepUpAuditEvent;
import cn.shangjingu.platform.iam.stepup.StepUpAuditSink;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcSecurityAuditService implements StepUpAuditSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSecurityAuditService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecurityAuditMode mode;
    private final AtomicLong writeFailureCount = new AtomicLong();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();

    public JdbcSecurityAuditService(String url, String username, String password) {
        this(url, username, password, SecurityAuditMode.FAIL_CLOSED);
    }

    public JdbcSecurityAuditService(String url, String username, String password, SecurityAuditMode mode) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mode = mode == null ? SecurityAuditMode.FAIL_OPEN : mode;
    }

    @Override
    public void record(StepUpAuditEvent event) {
        recordOperation(event.subject(), event.eventType(), "STEP_UP", null);
        if ("STEP_UP_REJECTED".equals(event.eventType())) {
            recordSecurityEvent(
                    event.subject().tenantId(),
                    event.subject().userId(),
                    event.subject().identityId(),
                    "STEP_UP_REJECTED",
                    "WARN",
                    event.outcome());
        }
    }

    public void recordOperation(SessionContext subject, String action, String resourceType, UUID resourceId) {
        recordOperation(subject.tenantId(), subject.userId(), subject.identityId(), action, resourceType, resourceId);
    }

    public void recordOperation(
            UUID tenantId, UUID userId, UUID identityId, String action, String resourceType, UUID resourceId) {
        inTenant(tenantId, userId, identityId, action, resourceType, () -> {
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            jdbc.update(
                    """
                    insert into audit.operation_log(
                        tenant_id,actor_id,actor_identity_id,action,resource_type,resource_id,request_id,correlation_id,trace_id)
                    values (?,?,?,?,?,?,?,?,?)
                    """,
                    tenantId,
                    userId,
                    identityId,
                    action,
                    resourceType,
                    resourceId,
                    requestId(),
                    correlation(trace),
                    traceId(trace));
        });
    }

    public void recordSensitiveAccess(
            SessionContext subject, String resourceType, UUID resourceId, String fieldsAccessedJson, String purpose) {
        inTenant(subject.tenantId(), subject.userId(), subject.identityId(), "SENSITIVE_ACCESS", resourceType, () -> {
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            jdbc.update(
                    """
                            insert into audit.access_log(
                                tenant_id,actor_id,resource_type,resource_id,fields_accessed,purpose,correlation_id,trace_id)
                            values (?,?,?,?,cast(? as jsonb),?,?,?)
                            """,
                    subject.tenantId(),
                    subject.userId(),
                    resourceType,
                    resourceId,
                    fieldsAccessedJson,
                    purpose,
                    correlation(trace),
                    traceId(trace));
        });
    }

    public void recordConfigurationChange(
            SessionContext subject,
            String action,
            String resourceType,
            UUID resourceId,
            String beforeJson,
            String afterJson) {
        recordOperation(subject, action, resourceType, resourceId);
        RequestAuditContext request = RequestAuditContext.current();
        String remoteAddress = request == null ? null : request.remoteAddress();
        String device = request == null ? null : request.deviceFingerprint();
        inTenant(subject.tenantId(), subject.userId(), subject.identityId(), "AUTHZ_CONFIG_CHANGED", action, () -> {
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            jdbc.update(
                    """
                            insert into audit.security_event(
                                tenant_id,event_type,severity,actor_id,ip_address,device_fingerprint,detail,correlation_id,trace_id)
                            values (?,?,?,?,cast(? as inet),?,jsonb_build_object(
                                'request_id', cast(? as text),
                                'identity_id', cast(? as text),
                                'resource_type', cast(? as text),
                                'resource_id', cast(? as text),
                                'before', cast(? as jsonb),
                                'after', cast(? as jsonb)),?,?)
                            """,
                    subject.tenantId(),
                    "AUTHZ_CONFIG_CHANGED",
                    "HIGH",
                    subject.userId(),
                    remoteAddress,
                    device,
                    requestId(),
                    subject.identityId() == null ? null : subject.identityId().toString(),
                    resourceType,
                    resourceId == null ? null : resourceId.toString(),
                    beforeJson,
                    afterJson,
                    correlation(trace),
                    traceId(trace));
        });
    }

    public void recordSecurityEvent(
            UUID tenantId, UUID userId, UUID identityId, String eventType, String severity, String outcome) {
        RequestAuditContext request = RequestAuditContext.current();
        String remoteAddress = request == null ? null : request.remoteAddress();
        String device = request == null ? null : request.deviceFingerprint();
        inTenant(tenantId, userId, identityId, eventType, outcome, () -> {
            PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
            jdbc.update(
                    """
                    insert into audit.security_event(
                        tenant_id,event_type,severity,actor_id,ip_address,device_fingerprint,detail,correlation_id,trace_id)
                    values (?,?,?,?,cast(? as inet),?,jsonb_build_object(
                        'request_id', cast(? as text),'identity_id', cast(? as text),'outcome', cast(? as text)),?,?)
                    """,
                    tenantId,
                    eventType,
                    severity,
                    userId,
                    remoteAddress,
                    device,
                    requestId(),
                    identityId == null ? null : identityId.toString(),
                    outcome,
                    correlation(trace),
                    traceId(trace));
        });
    }

    public boolean isAvailable() {
        try {
            Integer result = jdbc.queryForObject("select 1", Integer.class);
            return result != null && result == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public SecurityAuditMode mode() {
        return mode;
    }

    public long writeFailureCount() {
        return writeFailureCount.get();
    }

    public Instant lastFailureAt() {
        return lastFailureAt.get();
    }

    private void inTenant(
            UUID tenantId, UUID userId, UUID identityId, String eventType, String outcome, Runnable work) {
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                work.run();
            });
        } catch (RuntimeException exception) {
            long failures = writeFailureCount.incrementAndGet();
            Instant failedAt = Instant.now();
            lastFailureAt.set(failedAt);
            LOGGER.warn(
                    "Security audit fallback mode={} tenantId={} userId={} identityId={} eventType={} outcome={} requestId={} failureCount={}",
                    mode.configurationValue(),
                    tenantId,
                    userId,
                    identityId,
                    eventType,
                    outcome,
                    requestId(),
                    failures,
                    exception);
            if (mode == SecurityAuditMode.FAIL_CLOSED) {
                throw new SecurityAuditUnavailableException(exception);
            }
        }
    }

    private static String correlation(PlatformTraceContext trace) {
        return trace == null ? null : trace.correlationId();
    }

    private static String traceId(PlatformTraceContext trace) {
        return trace == null ? null : trace.traceId();
    }

    private static String requestId() {
        RequestAuditContext context = RequestAuditContext.current();
        return context == null ? "internal-" + UUID.randomUUID() : context.requestId();
    }
}
