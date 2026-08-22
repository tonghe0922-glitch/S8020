package cn.shangjingu.platform.core.event;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TransactionalOutboxService {
    public static final String PENDING = "PENDING";
    private final JdbcTemplate jdbc;

    public TransactionalOutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID enqueue(Command command) {
        validate(command);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("transactional outbox enqueue requires an active caller transaction");
        }
        String eventKey = command.eventKey().trim();
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    rs.next();
                    return null;
                },
                command.tenantId() + "|OUTBOX|" + eventKey);
        List<UUID> exact = jdbc.query(
                """
                select id from core.outbox_event
                where tenant_id=? and event_key=? and aggregate_type=? and aggregate_id=? and event_type=?
                  and event_version=? and payload=cast(? as jsonb) and not is_deleted
                order by created_at,id
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                command.tenantId(),
                eventKey,
                command.aggregateType().trim(),
                command.aggregateId(),
                command.eventType().trim(),
                command.eventVersion(),
                command.payload());
        if (exact.size() == 1) return exact.getFirst();
        if (exact.size() > 1)
            throw new OutboxConflictException("outbox event_key has ambiguous duplicate event evidence");
        Long existing = jdbc.queryForObject(
                "select count(*) from core.outbox_event where tenant_id=? and event_key=? and not is_deleted",
                Long.class,
                command.tenantId(),
                eventKey);
        if (existing != null && existing > 0)
            throw new OutboxConflictException("outbox event_key already exists with different event content");

        PlatformTraceContext trace = PlatformTraceContextHolder.currentOrNull();
        UUID eventId = UUID.randomUUID();
        try {
            int inserted = jdbc.update(
                    """
                    insert into core.outbox_event(
                        id,tenant_id,created_by,updated_by,aggregate_type,aggregate_id,event_type,event_version,
                        payload,event_key,publish_status,retry_count,correlation_id,trace_id)
                    values (?,?,?,?,?,?,?,?,cast(? as jsonb),?,'PENDING',0,?,?)
                    """,
                    eventId,
                    command.tenantId(),
                    command.actorId(),
                    command.actorId(),
                    command.aggregateType().trim(),
                    command.aggregateId(),
                    command.eventType().trim(),
                    command.eventVersion(),
                    command.payload(),
                    eventKey,
                    trace == null ? null : trace.correlationId(),
                    trace == null ? null : trace.traceId());
            if (inserted != 1) throw new IllegalStateException("outbox event insert did not affect exactly one row");
            return eventId;
        } catch (DuplicateKeyException duplicate) {
            throw new OutboxConflictException("outbox event identity conflicted with a concurrent writer");
        }
    }

    private static void validate(Command command) {
        if (command == null) throw new IllegalArgumentException("outbox command is required");
        require(command.tenantId() != null, "tenantId is required");
        require(command.aggregateId() != null, "aggregateId is required");
        requireText(command.aggregateType(), 64, "aggregateType");
        requireText(command.eventType(), 128, "eventType");
        requireText(command.eventKey(), 128, "eventKey");
        require(command.eventVersion() > 0, "eventVersion must be positive");
        requireText(command.payload(), Integer.MAX_VALUE, "payload");
    }

    private static void requireText(String value, int max, String field) {
        require(value != null && !value.isBlank(), field + " is required");
        require(value.length() <= max, field + " exceeds " + max + " characters");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }

    public record Command(
            UUID tenantId,
            UUID actorId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String payload,
            String eventKey) {}
}
