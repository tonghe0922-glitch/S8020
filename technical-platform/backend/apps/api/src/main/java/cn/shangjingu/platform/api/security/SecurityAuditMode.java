package cn.shangjingu.platform.api.security;

import java.util.Locale;

public enum SecurityAuditMode {
    FAIL_OPEN,
    FAIL_CLOSED;

    public static SecurityAuditMode parse(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_OPEN;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "fail-open", "fail_open", "open" -> FAIL_OPEN;
            case "fail-closed", "fail_closed", "closed" -> FAIL_CLOSED;
            default -> throw new IllegalArgumentException(
                    "sjg.security.audit.mode must be fail-open or fail-closed");
        };
    }

    public boolean isFailOpen() {
        return this == FAIL_OPEN;
    }
}
