package cn.shangjingu.platform.workflow;

public final class WorkflowException extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        CONFLICT,
        INVALID_ARGUMENT,
        INVALID_DEFINITION,
        IMMUTABLE_PUBLISHED_VERSION,
        ILLEGAL_ACTION,
        STALE_VERSION,
        NO_ELIGIBLE_APPROVER,
        FORBIDDEN
    }

    private final Code code;

    public WorkflowException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public WorkflowException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static WorkflowException notFound(String message) {
        return new WorkflowException(Code.NOT_FOUND, message);
    }

    public static WorkflowException conflict(String message) {
        return new WorkflowException(Code.CONFLICT, message);
    }

    public static WorkflowException invalid(String message) {
        return new WorkflowException(Code.INVALID_ARGUMENT, message);
    }
}
