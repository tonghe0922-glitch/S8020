package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.notification.NotificationDeliveryProvider;

/**
 * IN_APP delivery boundary. The durable notification.message row is the mailbox delivery fact, so
 * accepting this provider does not perform external I/O and is naturally idempotent by message id.
 */
public final class InAppNotificationDeliveryProvider implements NotificationDeliveryProvider {
    @Override
    public String channel() {
        return "IN_APP";
    }

    @Override
    public DeliveryResult deliver(DeliveryRequest request) {
        if (request == null || request.messageId() == null || request.tenantId() == null
                || request.recipientId() == null || !"IN_APP".equals(request.channel())) {
            return new DeliveryResult(false, null);
        }
        return new DeliveryResult(true, "in-app:" + request.messageId());
    }
}
