package cn.shangjingu.platform.integration;

/** Fail-closed conflict for stable external request/provider-event identities. */
public final class IntegrationConflictException extends RuntimeException {
    public IntegrationConflictException(String message) { super(message); }
}
