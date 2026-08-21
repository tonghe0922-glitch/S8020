package cn.shangjingu.platform.core.process;

public final class ProcessRejectedException extends RuntimeException {
    public ProcessRejectedException(String message) {
        super(message);
    }

    public ProcessRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
