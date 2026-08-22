package cn.shangjingu.platform.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Literal {{variable}} renderer. It never evaluates expressions or executable template code. */
public final class NotificationTemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_.-]{0,63})\\}\\}");
    private final ObjectMapper mapper;

    public NotificationTemplateRenderer(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("object mapper is required");
        this.mapper = mapper;
    }

    public Rendered render(
            String titleTemplate, String bodyTemplate, String variablesSchema, Map<String, String> variables) {
        if (bodyTemplate == null) throw new IllegalArgumentException("body template is required");
        Map<String, String> safeVariables = variables == null ? Map.of() : Map.copyOf(variables);
        safeVariables.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") || value == null) {
                throw new IllegalArgumentException("notification variable key/value is invalid");
            }
        });

        Set<String> declared = new HashSet<>();
        Set<String> required = new HashSet<>();
        if (variablesSchema != null && !variablesSchema.isBlank()) {
            try {
                JsonNode schema = mapper.readTree(variablesSchema);
                JsonNode properties = schema.path("properties");
                if (properties.isObject()) properties.fieldNames().forEachRemaining(declared::add);
                JsonNode requiredNode = schema.path("required");
                if (requiredNode.isArray()) {
                    requiredNode.forEach(node -> {
                        if (!node.isTextual())
                            throw new IllegalArgumentException("notification required variable must be text");
                        required.add(node.asText());
                    });
                }
            } catch (IllegalArgumentException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalArgumentException("notification variables_schema is invalid", failure);
            }
        }
        for (String key : required) {
            if (!safeVariables.containsKey(key))
                throw new IllegalArgumentException("missing required notification variable: " + key);
        }
        if (!declared.isEmpty()) {
            for (String key : safeVariables.keySet()) {
                if (!declared.contains(key))
                    throw new IllegalArgumentException("undeclared notification variable: " + key);
            }
        }

        Set<String> placeholders = new HashSet<>();
        collect(titleTemplate, placeholders);
        collect(bodyTemplate, placeholders);
        for (String key : placeholders) {
            if (!safeVariables.containsKey(key))
                throw new IllegalArgumentException("missing notification placeholder: " + key);
        }
        return new Rendered(replace(titleTemplate, safeVariables), replace(bodyTemplate, safeVariables));
    }

    private static void collect(String template, Set<String> target) {
        if (template == null) return;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) target.add(matcher.group(1));
        rejectMalformedToken(template.replaceAll("\\{\\{[A-Za-z][A-Za-z0-9_.-]{0,63}\\}\\}", ""));
    }

    private static String replace(String template, Map<String, String> variables) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(result, Matcher.quoteReplacement(variables.get(matcher.group(1))));
        matcher.appendTail(result);
        rejectMalformedToken(result.toString());
        return result.toString();
    }

    private static void rejectMalformedToken(String value) {
        if (value != null && (value.contains("{{") || value.contains("}}"))) {
            throw new IllegalArgumentException("malformed or unresolved notification placeholder");
        }
    }

    public record Rendered(String title, String body) {}
}
