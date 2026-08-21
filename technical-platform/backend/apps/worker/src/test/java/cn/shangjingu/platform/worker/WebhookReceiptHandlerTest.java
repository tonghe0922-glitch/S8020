package cn.shangjingu.platform.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.integration.WebhookIngressService;
import cn.shangjingu.platform.integration.WebhookProcessingService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookReceiptHandlerTest {
    @Test
    void workerConsumesWebhookOutboxAndOnlyThenMarksEvidenceProcessed() {
        WebhookProcessingService processing=mock(WebhookProcessingService.class);WebhookReceiptHandler handler=new WebhookReceiptHandler(processing);PlatformOutboxEvent event=event(WebhookIngressService.AGGREGATE_TYPE);
        handler.handle(event);verify(processing).markProcessed(event.tenantId(),event.aggregateId());
    }
    @Test
    void mismatchedAggregateFailsClosed() {
        WebhookProcessingService processing=mock(WebhookProcessingService.class);WebhookReceiptHandler handler=new WebhookReceiptHandler(processing);
        assertThrows(IllegalStateException.class,()->handler.handle(event("WRONG")));verifyNoInteractions(processing);
    }
    private static PlatformOutboxEvent event(String aggregateType){return new PlatformOutboxEvent(UUID.randomUUID(),UUID.randomUUID(),aggregateType,UUID.randomUUID(),WebhookIngressService.EVENT_TYPE,1,"{}","event-key","correlation","trace",0,Instant.now(),Instant.now());}
}
