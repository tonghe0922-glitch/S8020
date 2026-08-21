package cn.shangjingu.platform.core.trace;

/** Thread-bound propagation only; durable truth remains in database evidence columns. */
public final class PlatformTraceContextHolder {
    private static final ThreadLocal<PlatformTraceContext> CURRENT = new ThreadLocal<>();

    private PlatformTraceContextHolder() {}

    public static PlatformTraceContext currentOrNull() {
        return CURRENT.get();
    }

    public static Scope open(PlatformTraceContext context) {
        PlatformTraceContext previous = CURRENT.get();
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final PlatformTraceContext previous;
        private boolean closed;

        private Scope(PlatformTraceContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
