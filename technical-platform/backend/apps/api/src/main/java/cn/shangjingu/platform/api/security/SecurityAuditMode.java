package cn.shangjingu.platform.api.security;

import java.util.Locale;

public enum SecurityAuditMode {
    FAIL_OPEN("fail-open"),
    FAIL_CLOSED("fail-closed");

    private final String configurationValue;

    SecurityAuditMode(String configurationValue) {
        this.configurationValue = configurationValue;
    }

    public String configurationValue() {
        return configurationValue;
    }

    public static SecurityAuditMode parse(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_OPEN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SecurityAuditMode mode : values()) {
            if (mode.configurationValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("sjg.security.audit.mode must be fail-open or fail-closed");
    }
}
