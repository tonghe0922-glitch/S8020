package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import cn.shangjingu.platform.integration.WebhookIngressService;
import cn.shangjingu.platform.integration.WebhookProcessingService;

/** Worker-only consumer: HTTP ingress never performs downstream provider side effects inline. */
public final class WebhookReceiptHandler implements PlatformOutboxHandler {
    private final WebhookProcessingService processing;
    public WebhookReceiptHandler(WebhookProcessingService processing){this.processing=processing;}
    @Override public String eventType(){return WebhookIngressService.EVENT_TYPE;}
    @Override public String consumerName(){return "platform-webhook-receipt";}
    @Override public void handle(PlatformOutboxEvent event){if(!WebhookIngressService.AGGREGATE_TYPE.equals(event.aggregateType()))throw new IllegalStateException("webhook outbox aggregate type mismatch");processing.markProcessed(event.tenantId(),event.aggregateId());}
}
