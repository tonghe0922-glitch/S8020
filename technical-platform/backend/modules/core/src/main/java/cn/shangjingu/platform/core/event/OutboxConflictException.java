package cn.shangjingu.platform.core.event;

public final class OutboxConflictException extends RuntimeException {
    public OutboxConflictException(String message) {
        super(message);
    }
}
