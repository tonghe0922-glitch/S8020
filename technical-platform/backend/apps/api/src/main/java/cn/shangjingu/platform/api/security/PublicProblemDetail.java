package cn.shangjingu.platform.api.security;

import java.util.Locale;

/** 统一保护面向用户的错误详情，避免内部英文异常与实现细节直接暴露。 */
public final class PublicProblemDetail {
    private PublicProblemDetail() {}

    public static String localized(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return containsChinese(message) ? message : fallback;
    }

    public static boolean isNotFound(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("not found") || message.contains("不存在") || message.contains("未找到");
    }

    private static boolean containsChinese(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9fff);
    }
}
