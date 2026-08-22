package cn.shangjingu.platform.notification;

import java.util.UUID;

/** External delivery boundary. Provider implementations must honor the supplied idempotency key. */
public interface NotificationDeliveryProvider {
    String channel();

    DeliveryResult deliver(DeliveryRequest request);

    record DeliveryRequest(
            UUID tenantId,
            UUID messageId,
            String recipientType,
            UUID recipientId,
            String channel,
            String title,
            String body,
            String idempotencyKey) {}

    /** accepted means the provider accepted/submitted the send; it is not a delivery receipt. */
    record DeliveryResult(boolean accepted, String providerReference) {}
}
