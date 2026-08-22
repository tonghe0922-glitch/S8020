package cn.shangjingu.platform.core.trace;

import java.util.UUID;

/** Immutable correlation/trace identity that can be persisted across async boundaries. */
public record PlatformTraceContext(String correlationId, String traceId) {
    public PlatformTraceContext {
        correlationId = normalize(correlationId, "correlationId");
        traceId = normalize(traceId, "traceId");
    }

    public static PlatformTraceContext create() {
        return new PlatformTraceContext(
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    public static PlatformTraceContext fromNullable(String correlationId, String traceId) {
        if (correlationId == null && traceId == null) return null;
        if (correlationId == null || traceId == null) {
            throw new IllegalStateException("persisted trace evidence must contain both correlation_id and trace_id");
        }
        return new PlatformTraceContext(correlationId, traceId);
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > 128) throw new IllegalArgumentException(field + " exceeds 128 characters");
        return normalized;
    }
}
