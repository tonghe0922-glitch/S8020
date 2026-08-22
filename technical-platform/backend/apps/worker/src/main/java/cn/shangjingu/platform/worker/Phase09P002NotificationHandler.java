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

/** Converts durable P002 domain events into durable in-app notification messages. */
public final class Phase09P002NotificationHandler implements PlatformOutboxHandler {
    public static final String EVENT_TYPE = "P002_PERMISSION_REQUEST_EVENT";
    public static final String AGGREGATE_TYPE = "P002_PERMISSION_REQUEST";
    private static final String TEMPLATE = "P002_PERMISSION_EVENT";

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Phase09P002NotificationHandler(NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        if (notifications == null || jdbc == null || mapper == null) {
            throw new IllegalArgumentException("P002 notification dependencies are required");
        }
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String consumerName() {
        return "phase09-p002-notification";
    }

    @Override
    public void handle(PlatformOutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.aggregateType()) || event.aggregateId() == null) {
            throw new IllegalArgumentException("P002 outbox aggregate is invalid");
        }
        ensureTemplate(event.tenantId());
        JsonNode payload = parse(event.payload());
        String businessNo = requiredText(payload, "businessNo");
        String eventCode = requiredText(payload, "event");
        String nodeCode = requiredText(payload, "nodeCode");
        Set<UUID> recipients = recipients(payload.path("recipientEmployeeIds"));
        for (UUID recipient : recipients) {
            notifications.create(new NotificationService.CreateCommand(
                    event.tenantId(),
                    null,
                    "p002-notify:" + event.id() + ":" + recipient,
                    TEMPLATE,
                    "IN_APP",
                    "EMPLOYEE",
                    recipient,
                    Map.of(
                            "businessNo", businessNo,
                            "event", eventCode,
                            "nodeLabel", nodeLabel(nodeCode)),
                    (Instant) null));
        }
    }

    private void ensureTemplate(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("P002 notification tenant is required");
        String lockMaterial = tenantId + "|P002_PERMISSION_EVENT";
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    rs.next();
                    return null;
                },
                lockMaterial);
        jdbc.update(
                """
                insert into notification.template(
                    id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled)
                select gen_random_uuid(),?,'P002_PERMISSION_EVENT','IN_APP',
                       '权限申请进度：{{nodeLabel}}',
                       '权限申请 {{businessNo}} 当前状态：{{nodeLabel}}（事件 {{event}}）。',
                       cast(? as jsonb),true
                where not exists (
                    select 1 from notification.template
                    where tenant_id=? and template_code='P002_PERMISSION_EVENT' and not is_deleted)
                """,
                tenantId,
                "{\"type\":\"object\",\"properties\":{\"businessNo\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"nodeLabel\":{\"type\":\"string\"}},\"required\":[\"businessNo\",\"event\",\"nodeLabel\"]}",
                tenantId);
    }

    private JsonNode parse(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException("P002 event payload must be an object");
            return node;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("P002 event payload is invalid JSON", failure);
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("P002 event field is required: " + field);
        }
        return value.textValue();
    }

    private static Set<UUID> recipients(JsonNode values) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (values == null || !values.isArray()) return result;
        values.forEach(value -> {
            if (!value.isTextual()) return;
            try {
                result.add(UUID.fromString(value.textValue()));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("P002 notification recipient is not a UUID", invalid);
            }
        });
        return result;
    }

    private static String nodeLabel(String nodeCode) {
        return switch (nodeCode) {
            case "S02" -> "个人/项目补充授权申请";
            case "S03" -> "业务负责人确认";
            case "S04" -> "数据责任人复核";
            case "S05" -> "高风险权限审批";
            case "S06" -> "权限生效";
            case "S07" -> "定期复核";
            case "S08" -> "到期/调岗/离职回收";
            case "END" -> "已关闭";
            default -> throw new IllegalArgumentException("P002 event node is not source-backed: " + nodeCode);
        };
    }
}
