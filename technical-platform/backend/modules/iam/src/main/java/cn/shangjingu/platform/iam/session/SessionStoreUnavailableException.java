package cn.shangjingu.platform.iam.session;

public final class SessionStoreUnavailableException extends RuntimeException {
    public SessionStoreUnavailableException(Throwable cause) {
        super("session store unavailable", cause);
    }
}
