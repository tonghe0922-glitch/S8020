package cn.shangjingu.platform.core.event;

import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import java.time.Instant;
import java.util.UUID;

public record PlatformOutboxEvent(
        UUID id,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        String eventKey,
        String correlationId,
        String traceId,
        int retryCount,
        Instant createdAt,
        Instant updatedAt) {
    public PlatformTraceContext traceContext() {
        return PlatformTraceContext.fromNullable(correlationId, traceId);
    }
}
