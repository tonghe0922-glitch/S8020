package cn.shangjingu.platform.integration;

import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Tenant-scoped inbound webhook evidence and provider-event dedup service. */
public final class WebhookIngressService {
    public static final String EVENT_TYPE = "INTEGRATION_WEBHOOK_RECEIVED", AGGREGATE_TYPE = "INTEGRATION_WEBHOOK";
    private final JdbcTemplate jdbc;
    private final TransactionalOutboxService outbox;
    private final WebhookProcessingService processing;

    public WebhookIngressService(JdbcTemplate jdbc, TransactionalOutboxService outbox) {
        if (jdbc == null || outbox == null) throw new IllegalArgumentException("webhook dependencies are required");
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.processing = new WebhookProcessingService(jdbc);
    }

    public WebhookResult receive(
            UUID tenantId, UUID actorId, ReceiveCommand command, WebhookSignatureVerifier verifier) {
        requireActiveTransaction();
        if (tenantId == null || command == null || verifier == null)
            throw new IllegalArgumentException("webhook tenant/command/verifier required");
        require(command.endpointCode(), "endpointCode", 64);
        require(command.providerEventId(), "providerEventId", 128);
        require(command.eventType(), "eventType", 128);
        if (command.payload() == null || command.payload().isBlank())
            throw new IllegalArgumentException("payload required");
        UUID endpointId = endpointId(tenantId, command.endpointCode().trim());
        String payloadHash = sha256(command.payload());
        String lock = tenantId + "|webhook|" + endpointId + "|"
                + command.providerEventId().trim();
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    rs.next();
                    return null;
                },
                lock);
        Existing existing =
                existing(tenantId, endpointId, command.providerEventId().trim());
        if (existing != null) {
            if (!existing.eventType().equals(command.eventType().trim())
                    || !existing.payloadSha256().equals(payloadHash))
                throw new IntegrationConflictException("provider_event_id replay has different content");
            return new WebhookResult(existing.id(), true, existing.signatureValid(), existing.status());
        }
        boolean signatureValid = verifier.verify(new WebhookSignatureVerifier.WebhookRequest(
                command.endpointCode().trim(),
                command.providerEventId().trim(),
                command.eventType().trim(),
                command.payload(),
                command.signature()));
        UUID id = UUID.randomUUID();
        String status = signatureValid ? "RECEIVED" : "REJECTED";
        PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
        int inserted = jdbc.update(
                "insert into integration.webhook_event(id,tenant_id,endpoint_id,provider_event_id,event_type,payload,payload_sha256,signature_valid,processing_status,received_at,correlation_id,trace_id) values (?,?,?,?,?,cast(? as jsonb),?,?,?,now(),?,?)",
                id,
                tenantId,
                endpointId,
                command.providerEventId().trim(),
                command.eventType().trim(),
                command.payload(),
                payloadHash,
                signatureValid,
                status,
                trace == null ? null : trace.correlationId(),
                trace == null ? null : trace.traceId());
        if (inserted != 1) throw new IllegalStateException("webhook evidence insert failed");
        if (signatureValid)
            outbox.enqueue(new TransactionalOutboxService.Command(
                    tenantId,
                    actorId,
                    AGGREGATE_TYPE,
                    id,
                    EVENT_TYPE,
                    1,
                    "{\"webhookEventId\":\"" + id + "\"}",
                    "webhook:"
                            + sha256(tenantId + "|" + endpointId + "|"
                                    + command.providerEventId().trim())));
        return new WebhookResult(id, false, signatureValid, status);
    }

    public void markProcessed(UUID tenantId, UUID eventId) {
        processing.markProcessed(tenantId, eventId);
    }

    public void markFailed(UUID tenantId, UUID eventId, String errorCode, String errorMessage) {
        processing.markFailed(tenantId, eventId, errorCode, errorMessage);
    }

    private UUID endpointId(UUID tenantId, String code) {
        UUID id = jdbc.query(
                "select id from integration.endpoint where tenant_id=? and endpoint_code=? and enabled and not is_deleted",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId,
                code);
        if (id == null) throw new IllegalArgumentException("webhook endpoint not found or disabled");
        return id;
    }

    private Existing existing(UUID tenantId, UUID endpointId, String providerEventId) {
        return jdbc.query(
                "select id,event_type,payload_sha256,signature_valid,processing_status from integration.webhook_event where tenant_id=? and endpoint_id=? and provider_event_id=?",
                rs -> rs.next()
                        ? new Existing(
                                rs.getObject("id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("payload_sha256"),
                                rs.getBoolean("signature_valid"),
                                rs.getString("processing_status"))
                        : null,
                tenantId,
                endpointId,
                providerEventId);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void require(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(name + " is invalid");
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("webhook operation requires active tenant transaction");
    }

    public record ReceiveCommand(
            String endpointCode, String providerEventId, String eventType, String payload, String signature) {}

    public record WebhookResult(UUID id, boolean duplicate, boolean signatureValid, String status) {}

    private record Existing(UUID id, String eventType, String payloadSha256, boolean signatureValid, String status) {}
}
