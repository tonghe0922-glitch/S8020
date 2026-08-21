package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.iam.session.SessionContext;
import java.util.Objects;

/** Request-local bridge into the document module guard; not an authorization truth store. */
public final class FileDownloadAuthorizationContext {
    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private FileDownloadAuthorizationContext() {}

    public static Request requireCurrent() {
        Request request = CURRENT.get();
        if (request == null) throw new org.springframework.security.access.AccessDeniedException("file download authorization context is missing");
        return request;
    }

    public static Scope open(SessionContext subject, String stepUpTicket) {
        Objects.requireNonNull(subject, "subject");
        Request previous = CURRENT.get();
        CURRENT.set(new Request(subject, stepUpTicket));
        return new Scope(previous);
    }

    public record Request(SessionContext subject, String stepUpTicket) {}
    public static final class Scope implements AutoCloseable {
        private final Request previous; private boolean closed;
        private Scope(Request previous){this.previous=previous;}
        @Override public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
