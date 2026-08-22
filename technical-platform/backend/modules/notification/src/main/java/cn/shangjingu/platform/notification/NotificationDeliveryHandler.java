package cn.shangjingu.platform.notification;

import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Outbox handler that marks SENT only after a configured channel provider explicitly accepts the send. */
public final class NotificationDeliveryHandler implements PlatformOutboxHandler {
    private final JdbcTemplate jdbc;
    private final Map<String, NotificationDeliveryProvider> providers;

    public NotificationDeliveryHandler(JdbcTemplate jdbc, List<NotificationDeliveryProvider> providers) {
        if (jdbc == null || providers == null)
            throw new IllegalArgumentException("notification delivery dependencies are required");
        this.jdbc = jdbc;
        LinkedHashMap<String, NotificationDeliveryProvider> resolved = new LinkedHashMap<>();
        for (NotificationDeliveryProvider provider : providers) {
            if (provider == null
                    || provider.channel() == null
                    || provider.channel().isBlank()) {
                throw new IllegalArgumentException("notification provider channel is required");
            }
            NotificationDeliveryProvider previous =
                    resolved.putIfAbsent(provider.channel().trim(), provider);
            if (previous != null)
                throw new IllegalArgumentException("multiple notification providers for channel " + provider.channel());
        }
        this.providers = Map.copyOf(resolved);
    }

    @Override
    public String eventType() {
        return NotificationService.SEND_EVENT_TYPE;
    }

    @Override
    public String consumerName() {
        return "notification-delivery";
    }

    @Override
    public void handle(PlatformOutboxEvent event) {
        if (event == null || !NotificationService.AGGREGATE_TYPE.equals(event.aggregateType())) {
            throw new IllegalArgumentException("notification outbox aggregate is invalid");
        }
        Message message = jdbc.query(
                """
                select id,tenant_id,recipient_type,recipient_id,channel,title,body,status,scheduled_at,sent_at
                from notification.message
                where tenant_id=? and id=? and not is_deleted
                for update
                """,
                rs -> rs.next()
                        ? new Message(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("recipient_type"),
                                rs.getObject("recipient_id", UUID.class),
                                rs.getString("channel"),
                                rs.getString("title"),
                                rs.getString("body"),
                                rs.getString("status"),
                                rs.getObject("scheduled_at", OffsetDateTime.class),
                                rs.getObject("sent_at", OffsetDateTime.class))
                        : null,
                event.tenantId(),
                event.aggregateId());
        if (message == null) throw new IllegalStateException("notification message not found");
        if ("SENT".equals(message.status())) return;
        if (!NotificationService.PENDING.equals(message.status()))
            throw new IllegalStateException("notification message status is not sendable");
        if (message.scheduledAt() != null && message.scheduledAt().isAfter(OffsetDateTime.now())) {
            throw new IllegalStateException("notification message is not due yet");
        }
        NotificationDeliveryProvider provider = providers.get(message.channel());
        if (provider == null)
            throw new IllegalStateException("notification provider is not configured for channel " + message.channel());
        NotificationDeliveryProvider.DeliveryResult result =
                provider.deliver(new NotificationDeliveryProvider.DeliveryRequest(
                        message.tenantId(),
                        message.id(),
                        message.recipientType(),
                        message.recipientId(),
                        message.channel(),
                        message.title(),
                        message.body(),
                        event.eventKey()));
        if (result == null || !result.accepted())
            throw new IllegalStateException("notification provider did not accept send");
        int updated = jdbc.update(
                """
                update notification.message
                set status='SENT',sent_at=now(),updated_at=now()
                where tenant_id=? and id=? and status='PENDING' and not is_deleted
                """,
                message.tenantId(),
                message.id());
        if (updated != 1) throw new IllegalStateException("notification SENT transition conflict");
    }

    private record Message(
            UUID id,
            UUID tenantId,
            String recipientType,
            UUID recipientId,
            String channel,
            String title,
            String body,
            String status,
            OffsetDateTime scheduledAt,
            OffsetDateTime sentAt) {}
}
