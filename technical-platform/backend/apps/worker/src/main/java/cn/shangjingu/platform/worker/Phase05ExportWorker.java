package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.audit.SensitiveExportService;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class Phase05ExportWorker {
    static final int MAX_ATTEMPTS = 3;
    private final TenantTransactionRunner transactions;
    private final JdbcTemplate jdbc;
    private final SensitiveExportService exports;
    private final List<SensitiveExportService.SensitiveExportGenerator> generators;
    private final WorkerCriticalAuditService audit;

    public Phase05ExportWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            SensitiveExportService exports,
            List<SensitiveExportService.SensitiveExportGenerator> generators,
            WorkerCriticalAuditService audit) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.exports = exports;
        this.generators = List.copyOf(generators);
        this.audit = audit;
    }

    public void handle(UUID tenantId, UUID eventId, UUID requestId, int expectedVersion) {
        try {
            transactions.required(tenantId, () -> {
                String status = jdbc.query(
                        """
                        select publish_status from core.outbox_event
                        where tenant_id=? and id=? and aggregate_id=? and event_type='P019_GENERATE' and not is_deleted
                        """,
                        rs -> rs.next() ? rs.getString(1) : null,
                        tenantId,
                        eventId,
                        requestId);
                if (status == null) throw new ProcessRejectedException("sensitive export outbox event not found");
                if ("PUBLISHED".equals(status)) return null;
                if (!"PENDING".equals(status))
                    throw new ProcessRejectedException("sensitive export outbox event is not pending");
                SensitiveExportService.ExportRequest request = exports.find(
                                DatabaseSecurityContext.tenantOnly(tenantId), requestId)
                        .orElseThrow(() -> new ProcessRejectedException("sensitive export request not found"));
                if (!"S05".equals(request.status()) || request.versionNo() != expectedVersion) {
                    throw new ProcessRejectedException("sensitive export worker state/version mismatch");
                }
                audit.record(
                        tenantId,
                        "P019_WORKER_GENERATE_ATTEMPT",
                        "audit.data_export_request",
                        requestId,
                        Map.of(
                                "event_id",
                                eventId.toString(),
                                "expected_version",
                                expectedVersion,
                                "export_type",
                                request.exportType()));
                SensitiveExportService.SensitiveExportGenerator generator = resolve(request.exportType());
                SensitiveExportService.GenerationResult result = generator.generate(
                        request, exports.items(DatabaseSecurityContext.tenantOnly(tenantId), requestId));
                exports.recordGenerated(
                        DatabaseSecurityContext.tenantOnly(tenantId), requestId, expectedVersion, result);
                if (jdbc.update(
                                """
                        update core.outbox_event set publish_status='PUBLISHED',published_at=now(),updated_at=now()
                        where tenant_id=? and id=? and event_type='P019_GENERATE' and publish_status='PENDING'
                        """,
                                tenantId,
                                eventId)
                        != 1) {
                    throw new ProcessRejectedException("sensitive export outbox acknowledgement conflict");
                }
                return null;
            });
        } catch (RuntimeException failure) {
            recordFailure(tenantId, eventId, requestId, expectedVersion, failure);
            throw failure;
        }
    }

    SensitiveExportService.SensitiveExportGenerator resolve(String exportType) {
        List<SensitiveExportService.SensitiveExportGenerator> matches = generators.stream()
                .filter(g -> exportType.equals(g.exportType()))
                .toList();
        if (matches.size() != 1) {
            throw new ProcessRejectedException(
                    "sensitive export generator is unavailable or ambiguous for type: " + exportType);
        }
        return matches.getFirst();
    }

    private void recordFailure(
            UUID tenantId, UUID eventId, UUID requestId, int expectedVersion, RuntimeException failure) {
        transactions.required(tenantId, () -> {
            Integer retry = jdbc.query(
                    """
                    update core.outbox_event set retry_count=retry_count+1,updated_at=now()
                    where tenant_id=? and id=? and event_type='P019_GENERATE' and publish_status='PENDING'
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
                        select ?,tenant_id,'P019_GENERATE',cast(id as text),
                               jsonb_build_object('request_id',cast(? as text),'expected_version',cast(? as integer),'event_id',cast(? as text)),
                               ?,?,'OPEN'
                        from core.outbox_event
                        where tenant_id=? and id=? and event_type='P019_GENERATE'
                          and not exists (select 1 from integration.dead_letter d where d.tenant_id=?
                            and d.source_type='P019_GENERATE' and d.source_id=cast(? as text) and not d.is_deleted)
                        """,
                        UUID.randomUUID(),
                        requestId.toString(),
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
                        where tenant_id=? and id=? and event_type='P019_GENERATE' and publish_status='PENDING'
                        """,
                        tenantId,
                        eventId);
            }
            return null;
        });
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) return "generation failed";
        String compact = message.replaceAll("[\\r\\n\\t]", " ");
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }
}
