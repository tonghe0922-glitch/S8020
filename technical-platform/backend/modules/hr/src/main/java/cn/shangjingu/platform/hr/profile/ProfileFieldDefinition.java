package cn.shangjingu.platform.hr.profile;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.Locale;

public enum ProfileFieldDefinition {
    PERSON_NAME("person_name", "P2-个人", 128, false),
    MOBILE("mobile", "P2-个人", 32, false),
    ID_NO("id_no", "P3-高度敏感", 32, true);

    private final String code;
    private final String sensitivity;
    private final int maxLength;
    private final boolean proofRequired;

    ProfileFieldDefinition(String code, String sensitivity, int maxLength, boolean proofRequired) {
        this.code = code;
        this.sensitivity = sensitivity;
        this.maxLength = maxLength;
        this.proofRequired = proofRequired;
    }

    public String code() { return code; }
    public String sensitivity() { return sensitivity; }
    public boolean proofRequired() { return proofRequired; }

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new ProcessRejectedException("P003 proposed value is required for " + code);
        String value = raw.trim();
        if (value.length() > maxLength) throw new ProcessRejectedException("P003 proposed value is too long for " + code);
        return switch (this) {
            case PERSON_NAME -> value;
            case MOBILE -> normalizeMobile(value);
            case ID_NO -> normalizeIdNo(value);
        };
    }

    public static ProfileFieldDefinition fromCode(String raw) {
        if (raw == null || raw.isBlank()) throw new ProcessRejectedException("P003 profile field code is required");
        String code = raw.trim().toLowerCase(Locale.ROOT);
        for (ProfileFieldDefinition value : values()) if (value.code.equals(code)) return value;
        throw new ProcessRejectedException("P003 profile field is not self-service enabled: " + raw);
    }

    private static String normalizeMobile(String value) {
        String normalized = value.replace(" ", "").replace("-", "");
        if (!normalized.matches("^\\+?[0-9]{6,20}$")) throw new ProcessRejectedException("P003 mobile format is invalid");
        return normalized;
    }

    private static String normalizeIdNo(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[0-9A-Z]{6,32}$")) throw new ProcessRejectedException("P003 identity number format is invalid");
        return normalized;
    }
}
