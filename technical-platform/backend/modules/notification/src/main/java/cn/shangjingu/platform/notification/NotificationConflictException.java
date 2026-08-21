package cn.shangjingu.platform.notification;

/** Fail-closed conflict for deterministic notification request identity. */
public final class NotificationConflictException extends RuntimeException {
    public NotificationConflictException(String message) {
        super(message);
    }
}
