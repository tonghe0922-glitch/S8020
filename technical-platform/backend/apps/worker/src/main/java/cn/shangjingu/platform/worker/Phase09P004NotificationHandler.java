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

/** Converts durable P004 generic-request workflow events into durable in-app notifications. */
public final class Phase09P004NotificationHandler implements PlatformOutboxHandler {
    public static final String EVENT_TYPE = "P004_GENERIC_REQUEST_EVENT";
    public static final String AGGREGATE_TYPE = "P004_GENERIC_REQUEST";
    private static final String TEMPLATE = "P004_GENERIC_REQUEST_EVENT";

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Phase09P004NotificationHandler(NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        if (notifications == null || jdbc == null || mapper == null) throw new IllegalArgumentException("P004 notification dependencies are required");
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public String eventType() { return EVENT_TYPE; }
    @Override public String consumerName() { return "phase09-p004-notification"; }

    @Override
    public void handle(PlatformOutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.aggregateType()) || event.aggregateId() == null) {
            throw new IllegalArgumentException("P004 outbox aggregate is invalid");
        }
        ensureTemplate(event.tenantId());
        JsonNode payload = parse(event.payload());
        String businessNo = requiredText(payload, "businessNo");
        String eventCode = requiredText(payload, "event");
        String nodeCode = requiredText(payload, "nodeCode");
        for (UUID recipient : recipients(payload.path("recipientEmployeeIds"))) {
            notifications.create(new NotificationService.CreateCommand(
                    event.tenantId(), null, "p004-notify:" + event.id() + ":" + recipient,
                    TEMPLATE, "IN_APP", "EMPLOYEE", recipient,
                    Map.of("businessNo", businessNo, "event", eventCode, "nodeLabel", nodeLabel(nodeCode)), (Instant) null));
        }
    }

    private void ensureTemplate(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("P004 notification tenant is required");
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))", rs -> { rs.next(); return null; },
                tenantId + "|" + TEMPLATE);
        jdbc.update("""
                insert into notification.template(
                    id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled)
                select gen_random_uuid(),?,'P004_GENERIC_REQUEST_EVENT','IN_APP',
                       '通用申请进度：{{nodeLabel}}',
                       '通用申请 {{businessNo}} 当前状态：{{nodeLabel}}（事件 {{event}}）。',
                       cast(? as jsonb),true
                where not exists (
                    select 1 from notification.template
                    where tenant_id=? and template_code='P004_GENERIC_REQUEST_EVENT' and not is_deleted)
                """, tenantId,
                "{\"type\":\"object\",\"properties\":{\"businessNo\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"nodeLabel\":{\"type\":\"string\"}},\"required\":[\"businessNo\",\"event\",\"nodeLabel\"]}",
                tenantId);
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode value = mapper.readTree(raw);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("P004 event payload must be an object");
            return value;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("P004 event payload is invalid JSON", ex);
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("P004 event field is required: " + field);
        }
        return value.textValue();
    }

    private static Set<UUID> recipients(JsonNode values) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (values == null || !values.isArray()) return result;
        values.forEach(value -> {
            if (!value.isTextual()) return;
            try { result.add(UUID.fromString(value.textValue())); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("P004 notification recipient is not a UUID", ex); }
        });
        return result;
    }

    private static String nodeLabel(String node) {
        return switch (node) {
            case "S02" -> "填写申请与附件";
            case "S03" -> "前置规则校验";
            case "S04" -> "提交审批";
            case "S05" -> "动态审批与会签";
            case "S06" -> "批准后生成执行任务";
            case "S07" -> "执行人提交结果";
            case "S08" -> "独立验收";
            case "S09" -> "异常补偿";
            case "S10" -> "归档";
            case "END" -> "已关闭";
            default -> throw new IllegalArgumentException("P004 event node is not source-backed: " + node);
        };
    }
}
