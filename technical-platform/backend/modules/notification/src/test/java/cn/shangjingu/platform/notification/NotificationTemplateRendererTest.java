package cn.shangjingu.platform.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationTemplateRendererTest {
    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer(new ObjectMapper());

    @Test
    void rendersLiteralVariablesWithoutExpressionEvaluation() {
        String schema = "{\"properties\":{\"name\":{},\"code\":{}},\"required\":[\"name\",\"code\"]}";
        NotificationTemplateRenderer.Rendered rendered = renderer.render(
                "Hello {{name}}", "Code={{code}}", schema, Map.of("name", "$1\\value", "code", "A-01"));
        assertEquals("Hello $1\\value", rendered.title());
        assertEquals("Code=A-01", rendered.body());
    }

    @Test
    void missingUndeclaredOrMalformedVariablesFailClosed() {
        String schema = "{\"properties\":{\"name\":{}},\"required\":[\"name\"]}";
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, "Hi {{name}}", schema, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, "Hi {{name}}", schema, Map.of("name", "A", "extra", "B")));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, "Hi {{name", schema, Map.of("name", "A")));
    }
}
