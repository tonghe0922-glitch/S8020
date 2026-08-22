package cn.shangjingu.platform.notification;

import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Tenant-scoped template rendering, message creation and due-message Outbox scheduling. */
public final class NotificationService {
    public static final String PENDING = "PENDING";
    public static final String SEND_EVENT_TYPE = "NOTIFICATION_SEND";
    public static final String AGGREGATE_TYPE = "NOTIFICATION_MESSAGE";

    private final JdbcTemplate jdbc;
    private final TransactionalOutboxService outbox;
    private final NotificationTemplateRenderer renderer;

    public NotificationService(
            JdbcTemplate jdbc, TransactionalOutboxService outbox, NotificationTemplateRenderer renderer) {
        if (jdbc == null || outbox == null || renderer == null)
            throw new IllegalArgumentException("notification dependencies are required");
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.renderer = renderer;
    }

    public UUID create(CreateCommand command) {
        requireActiveTransaction();
        validate(command);
        String requestKey = command.requestKey().trim();
        String lockMaterial = command.tenantId() + "|NOTIFICATION_CREATE|" + requestKey;
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    rs.next();
                    return null;
                },
                lockMaterial);

        Template template = loadTemplate(command.tenantId(), command.templateCode(), command.channel());
        NotificationTemplateRenderer.Rendered rendered = renderer.render(
                template.titleTemplate(), template.bodyTemplate(), template.variablesSchema(), command.variables());
        Instant scheduledAt = normalize(command.scheduledAt());
        String messageNo = messageNo(command.tenantId(), requestKey);
        ExistingMessage existing = findByMessageNo(command.tenantId(), messageNo);
        if (existing != null) {
            if (same(existing, template.id(), command, rendered, scheduledAt)) return existing.id();
            throw new NotificationConflictException(
                    "notification request key already exists with different message content");
        }

        UUID messageId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                insert into notification.message(
                    id,tenant_id,created_by,updated_by,message_no,template_id,recipient_type,recipient_id,
                    channel,title,body,status,scheduled_at)
                values (?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)
                """,
                messageId,
                command.tenantId(),
                command.actorId(),
                command.actorId(),
                messageNo,
                template.id(),
                command.recipientType().trim(),
                command.recipientId(),
                command.channel().trim(),
                rendered.title(),
                rendered.body(),
                offset(scheduledAt));
        if (inserted != 1) throw new IllegalStateException("notification message insert failed");
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now()))
            enqueueSend(command.tenantId(), command.actorId(), messageId);
        return messageId;
    }

    public int enqueueDue(UUID tenantId, UUID actorId, int limit) {
        requireActiveTransaction();
        if (tenantId == null || limit <= 0 || limit > 500)
            throw new IllegalArgumentException("tenant and due-message limit are required");
        List<UUID> ids = jdbc.query(
                """
                select m.id
                from notification.message m
                where m.tenant_id=? and m.status='PENDING' and not m.is_deleted
                  and m.scheduled_at is not null and m.scheduled_at <= now()
                  and not exists (
                    select 1 from core.outbox_event o
                    where o.tenant_id=m.tenant_id and o.aggregate_type='NOTIFICATION_MESSAGE'
                      and o.aggregate_id=m.id and o.event_type='NOTIFICATION_SEND' and not o.is_deleted)
                order by m.scheduled_at,m.created_at,m.id
                for update skip locked
                limit ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId,
                limit);
        ids.forEach(id -> enqueueSend(tenantId, actorId, id));
        return ids.size();
    }

    private void enqueueSend(UUID tenantId, UUID actorId, UUID messageId) {
        outbox.enqueue(new TransactionalOutboxService.Command(
                tenantId,
                actorId,
                AGGREGATE_TYPE,
                messageId,
                SEND_EVENT_TYPE,
                1,
                "{\"messageId\":\"" + messageId + "\"}",
                "notification-send:" + messageId));
    }

    private Template loadTemplate(UUID tenantId, String templateCode, String channel) {
        Template template = jdbc.query(
                """
                select id,channel,title_template,body_template,variables_schema::text as variables_schema
                from notification.template
                where tenant_id=? and template_code=? and enabled and not is_deleted
                """,
                rs -> rs.next()
                        ? new Template(
                                rs.getObject("id", UUID.class),
                                rs.getString("channel"),
                                rs.getString("title_template"),
                                rs.getString("body_template"),
                                rs.getString("variables_schema"))
                        : null,
                tenantId,
                templateCode.trim());
        if (template == null) throw new IllegalArgumentException("notification template not found or disabled");
        if (!template.channel().equals(channel.trim()))
            throw new IllegalArgumentException("notification template channel mismatch");
        return template;
    }

    private ExistingMessage findByMessageNo(UUID tenantId, String messageNo) {
        return jdbc.query(
                """
                select id,template_id,recipient_type,recipient_id,channel,title,body,scheduled_at
                from notification.message
                where tenant_id=? and message_no=? and not is_deleted
                """,
                rs -> rs.next()
                        ? new ExistingMessage(
                                rs.getObject("id", UUID.class),
                                rs.getObject("template_id", UUID.class),
                                rs.getString("recipient_type"),
                                rs.getObject("recipient_id", UUID.class),
                                rs.getString("channel"),
                                rs.getString("title"),
                                rs.getString("body"),
                                instant(rs.getObject("scheduled_at", OffsetDateTime.class)))
                        : null,
                tenantId,
                messageNo);
    }

    private static boolean same(
            ExistingMessage existing,
            UUID templateId,
            CreateCommand command,
            NotificationTemplateRenderer.Rendered rendered,
            Instant scheduledAt) {
        return Objects.equals(existing.templateId(), templateId)
                && Objects.equals(
                        existing.recipientType(), command.recipientType().trim())
                && Objects.equals(existing.recipientId(), command.recipientId())
                && Objects.equals(existing.channel(), command.channel().trim())
                && Objects.equals(existing.title(), rendered.title())
                && Objects.equals(existing.body(), rendered.body())
                && Objects.equals(existing.scheduledAt(), scheduledAt);
    }

    static String messageNo(UUID tenantId, String requestKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "|" + requestKey).getBytes(StandardCharsets.UTF_8));
            return "MSG-" + HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Instant normalize(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void validate(CreateCommand command) {
        if (command == null || command.tenantId() == null)
            throw new IllegalArgumentException("notification tenant is required");
        requireText(command.requestKey(), "requestKey", 128);
        requireText(command.templateCode(), "templateCode", 64);
        requireText(command.channel(), "channel", 32);
        requireText(command.recipientType(), "recipientType", 32);
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException(field + " is invalid");
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("notification operation requires an active tenant transaction");
        }
    }

    public record CreateCommand(
            UUID tenantId,
            UUID actorId,
            String requestKey,
            String templateCode,
            String channel,
            String recipientType,
            UUID recipientId,
            Map<String, String> variables,
            Instant scheduledAt) {}

    private record Template(
            UUID id, String channel, String titleTemplate, String bodyTemplate, String variablesSchema) {}

    private record ExistingMessage(
            UUID id,
            UUID templateId,
            String recipientType,
            UUID recipientId,
            String channel,
            String title,
            String body,
            Instant scheduledAt) {}
}
