package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import cn.shangjingu.platform.notification.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Converts durable P003 workflow events into durable in-app messages without exposing changed values. */
public final class Phase09P003NotificationHandler implements PlatformOutboxHandler {
    public static final String EVENT_TYPE = "P003_PROFILE_CHANGE_EVENT";
    public static final String AGGREGATE_TYPE = "P003_PROFILE_CHANGE";
    private static final String TEMPLATE = "P003_PROFILE_CHANGE_EVENT";

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Phase09P003NotificationHandler(NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        if (notifications == null || jdbc == null || mapper == null) throw new IllegalArgumentException("P003 notification dependencies are required");
        this.notifications = notifications; this.jdbc = jdbc; this.mapper = mapper;
    }

    @Override public String eventType() { return EVENT_TYPE; }
    @Override public String consumerName() { return "phase09-p003-notification"; }

    @Override
    public void handle(PlatformOutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.aggregateType()) || event.aggregateId() == null)
            throw new IllegalArgumentException("P003 outbox aggregate is invalid");
        ensureTemplate(event.tenantId());
        JsonNode payload = parse(event.payload());
        String businessNo = requiredText(payload, "businessNo");
        String eventCode = requiredText(payload, "event");
        String nodeCode = requiredText(payload, "nodeCode");
        for (UUID recipient : recipients(payload.path("recipientEmployeeIds"))) {
            notifications.create(new NotificationService.CreateCommand(
                    event.tenantId(), null, "p003-notify:" + event.id() + ":" + recipient,
                    TEMPLATE, "IN_APP", "EMPLOYEE", recipient,
                    Map.of("businessNo", businessNo, "event", eventCode, "nodeLabel", nodeLabel(nodeCode)), (Instant) null));
        }
    }

    private void ensureTemplate(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("P003 notification tenant is required");
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))", rs -> { rs.next(); return null; },
                tenantId + "|" + TEMPLATE);
        jdbc.update("""
                insert into notification.template(
                    id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled)
                select gen_random_uuid(),?,'P003_PROFILE_CHANGE_EVENT','IN_APP',
                       '个人资料变更进度：{{nodeLabel}}',
                       '个人资料变更 {{businessNo}} 当前状态：{{nodeLabel}}（事件 {{event}}）。',
                       cast(? as jsonb),true
                where not exists (
                    select 1 from notification.template
                    where tenant_id=? and template_code='P003_PROFILE_CHANGE_EVENT' and not is_deleted)
                """, tenantId,
                "{\"type\":\"object\",\"properties\":{\"businessNo\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"nodeLabel\":{\"type\":\"string\"}},\"required\":[\"businessNo\",\"event\",\"nodeLabel\"]}",
                tenantId);
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode value = mapper.readTree(raw);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("P003 event payload must be an object");
            return value;
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("P003 event payload is invalid JSON", ex); }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("P003 event field is required: " + field);
        return value.textValue();
    }

    private static Set<UUID> recipients(JsonNode values) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (values == null || !values.isArray()) return result;
        values.forEach(value -> {
            if (!value.isTextual()) return;
            try { result.add(UUID.fromString(value.textValue())); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("P003 notification recipient is not a UUID", ex); }
        });
        return result;
    }

    private static String nodeLabel(String node) {
        return switch (node) {
            case "S04" -> "字段敏感级别校验";
            case "S05" -> "人事/财务/归口岗核验";
            case "S06" -> "权威主档更新";
            case "S07" -> "关联模块投影同步";
            case "S08" -> "通知与审计";
            case "END" -> "已关闭";
            default -> throw new IllegalArgumentException("P003 event node is not source-backed: " + node);
        };
    }
}
