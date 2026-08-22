package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.integration.DataImportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class Phase05ImportWorker {
    static final int MAX_ATTEMPTS = 3;

    private final TenantTransactionRunner transactions;
    private final JdbcTemplate jdbc;
    private final DataImportService imports;
    private final List<DataImportService.ImportExecutor> executors;
    private final WorkerCriticalAuditService audit;

    public Phase05ImportWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            DataImportService imports,
            List<DataImportService.ImportExecutor> executors,
            WorkerCriticalAuditService audit) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.imports = imports;
        this.executors = List.copyOf(executors);
        this.audit = audit;
    }

    /** Handles one already-routed tenant-scoped outbox event; it never scans tenants or bypasses RLS. */
    public void handle(UUID tenantId, UUID eventId, UUID jobId, int expectedVersion) {
        try {
            transactions.required(tenantId, () -> {
                OutboxEvent event = loadEvent(tenantId, eventId, jobId);
                if ("PUBLISHED".equals(event.publishStatus())) return null;
                if ("DEAD_LETTER".equals(event.publishStatus())) {
                    throw new ProcessRejectedException("data import event is already dead-lettered");
                }
                DataImportService.DataImportJob job = imports.find(DatabaseSecurityContext.tenantOnly(tenantId), jobId)
                        .orElseThrow(() -> new ProcessRejectedException("data import job not found for worker event"));
                if (!"S08".equals(job.status()) || job.versionNo() != expectedVersion) {
                    throw new ProcessRejectedException("data import worker event version/state mismatch");
                }
                audit.record(
                        tenantId,
                        "P018_WORKER_EXECUTE_ATTEMPT",
                        "integration.data_import_job",
                        jobId,
                        Map.of(
                                "event_id",
                                eventId.toString(),
                                "expected_version",
                                expectedVersion,
                                "import_type",
                                job.importType()));
                DataImportService.ImportExecutor executor = resolveExecutor(job.importType());
                DataImportService.ExecutionResult result =
                        executor.execute(job, imports.items(DatabaseSecurityContext.tenantOnly(tenantId), jobId));
                imports.recordExecutionResult(
                        DatabaseSecurityContext.tenantOnly(tenantId), jobId, expectedVersion, result);
                int published = jdbc.update(
                        """
                        update core.outbox_event
                        set publish_status='PUBLISHED',published_at=now(),updated_at=now()
                        where tenant_id=? and id=? and event_type='P018_EXECUTE' and publish_status='PENDING'
                        """,
                        tenantId,
                        eventId);
                if (published != 1)
                    throw new ProcessRejectedException("data import outbox publish acknowledgement conflict");
                return null;
            });
        } catch (RuntimeException failure) {
            recordFailure(tenantId, eventId, jobId, expectedVersion, failure);
            throw failure;
        }
    }

    DataImportService.ImportExecutor resolveExecutor(String importType) {
        List<DataImportService.ImportExecutor> matches = executors.stream()
                .filter(executor -> importType.equals(executor.importType()))
                .toList();
        if (matches.size() != 1) {
            throw new ProcessRejectedException(
                    "data import executor is unavailable or ambiguous for type: " + importType);
        }
        return matches.getFirst();
    }

    private OutboxEvent loadEvent(UUID tenantId, UUID eventId, UUID jobId) {
        OutboxEvent event = jdbc.query(
                """
                select id,aggregate_id,publish_status,retry_count
                from core.outbox_event
                where tenant_id=? and id=? and event_type='P018_EXECUTE' and not is_deleted
                """,
                rs -> rs.next()
                        ? new OutboxEvent(
                                rs.getObject("id", UUID.class),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getString("publish_status"),
                                rs.getInt("retry_count"))
                        : null,
                tenantId,
                eventId);
        if (event == null || !jobId.equals(event.aggregateId())) {
            throw new ProcessRejectedException("data import outbox event is missing or mismatched");
        }
        return event;
    }

    private void recordFailure(UUID tenantId, UUID eventId, UUID jobId, int expectedVersion, RuntimeException failure) {
        transactions.required(tenantId, () -> {
            Integer retry = jdbc.query(
                    """
                    update core.outbox_event set retry_count=retry_count+1,updated_at=now()
                    where tenant_id=? and id=? and event_type='P018_EXECUTE' and publish_status='PENDING'
                    returning retry_count
                    """,
                    rs -> rs.next() ? rs.getInt(1) : null,
                    tenantId,
                    eventId);
            if (retry != null && retry >= MAX_ATTEMPTS) {
                String message = sanitize(failure.getMessage());
                jdbc.update(
                        """
                        insert into integration.dead_letter(id,tenant_id,source_type,source_id,payload,error_code,error_message,status)
                        select ?,tenant_id,'P018_EXECUTE',cast(id as text),
                               jsonb_build_object('job_id',cast(? as text),'expected_version',cast(? as integer),'event_id',cast(? as text)),
                               ?,?,'OPEN'
                        from core.outbox_event
                        where tenant_id=? and id=? and event_type='P018_EXECUTE'
                          and not exists (select 1 from integration.dead_letter d where d.tenant_id=?
                            and d.source_type='P018_EXECUTE' and d.source_id=cast(? as text) and not d.is_deleted)
                        """,
                        UUID.randomUUID(),
                        jobId.toString(),
                        expectedVersion,
                        eventId.toString(),
                        failure.getClass().getSimpleName(),
                        message,
                        tenantId,
                        eventId,
                        tenantId,
                        eventId.toString());
                jdbc.update(
                        """
                        update core.outbox_event set publish_status='DEAD_LETTER',updated_at=now()
                        where tenant_id=? and id=? and event_type='P018_EXECUTE' and publish_status='PENDING'
                        """,
                        tenantId,
                        eventId);
            }
            return null;
        });
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) return "execution failed";
        String compact = message.replaceAll("[\\r\\n\\t]", " ");
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private record OutboxEvent(UUID id, UUID aggregateId, String publishStatus, int retryCount) {}
}
