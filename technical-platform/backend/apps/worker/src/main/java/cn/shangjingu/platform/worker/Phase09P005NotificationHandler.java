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

/** P005 durable notice delivery and privacy-minimized progress notifications. */
public final class Phase09P005NotificationHandler implements PlatformOutboxHandler {
    public static final String EVENT_TYPE = "P005_NOTICE_EVENT";
    public static final String AGGREGATE_TYPE = "P005_NOTICE";
    private static final String TEMPLATE = "P005_NOTICE_EVENT";
    private static final String DELIVERY_EVENT = "P005.stage.03.completed";

    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Phase09P005NotificationHandler(NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        if (notifications == null || jdbc == null || mapper == null) {
            throw new IllegalArgumentException("P005 notification dependencies are required");
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
        return "phase09-p005-notification";
    }

    @Override
    public void handle(PlatformOutboxEvent event) {
        if (event == null || !AGGREGATE_TYPE.equals(event.aggregateType()) || event.aggregateId() == null) {
            throw new IllegalArgumentException("P005 outbox aggregate is invalid");
        }
        ensureTemplate(event.tenantId());
        JsonNode payload = parse(event.payload());
        String businessNo = requiredText(payload, "businessNo");
        String eventCode = requiredText(payload, "event");
        String nodeCode = requiredText(payload, "nodeCode");
        for (UUID recipient : recipients(payload.path("recipientEmployeeIds"))) {
            notifications.create(new NotificationService.CreateCommand(
                    event.tenantId(),
                    null,
                    "p005-notify:" + event.id() + ":" + recipient,
                    TEMPLATE,
                    "IN_APP",
                    "EMPLOYEE",
                    recipient,
                    Map.of("businessNo", businessNo, "event", eventCode, "nodeLabel", nodeLabel(nodeCode)),
                    (Instant) null));
            if (DELIVERY_EVENT.equals(eventCode)) markDelivered(event.tenantId(), event.aggregateId(), recipient);
        }
    }

    private void markDelivered(UUID tenantId, UUID noticeId, UUID employeeId) {
        int changed = jdbc.update(
                """
                update collaboration.notice_recipient
                   set delivery_status='DELIVERED',delivered_at=now(),version_no=version_no+1,updated_at=now()
                 where tenant_id=? and notice_id=? and employee_id=? and delivered_at is null and not is_deleted
                """,
                tenantId,
                noticeId,
                employeeId);
        if (changed == 0) return;
        if (changed != 1) throw new IllegalStateException("P005 delivery resolved multiple recipient rows");
        int appended = jdbc.update(
                """
                insert into collaboration.notice_receipt_event(
                    id,tenant_id,notice_id,recipient_id,employee_id,actor_employee_id,event_type,evidence_json)
                select gen_random_uuid(),r.tenant_id,r.notice_id,r.id,r.employee_id,n.owner_employee_id,'DELIVERED','{}'::jsonb
                  from collaboration.notice_recipient r
                  join collaboration.notice n on n.tenant_id=r.tenant_id and n.id=r.notice_id and not n.is_deleted
                 where r.tenant_id=? and r.notice_id=? and r.employee_id=? and not r.is_deleted
                   and not exists (
                     select 1 from collaboration.notice_receipt_event e
                      where e.tenant_id=r.tenant_id and e.recipient_id=r.id and e.event_type='DELIVERED')
                """,
                tenantId,
                noticeId,
                employeeId);
        if (appended != 1)
            throw new IllegalStateException("P005 durable delivery receipt was not appended exactly once");
    }

    private void ensureTemplate(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("P005 notification tenant is required");
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    if (rs.next()) rs.getObject(1);
                    return null;
                },
                tenantId + "|" + TEMPLATE);
        jdbc.update(
                """
                insert into notification.template(
                    id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled)
                select gen_random_uuid(),?,'P005_NOTICE_EVENT','IN_APP',
                       '制度通知进度：{{nodeLabel}}',
                       '制度通知 {{businessNo}} 当前节点：{{nodeLabel}}（事件 {{event}}）。',
                       cast(? as jsonb),true
                where not exists (
                    select 1 from notification.template
                    where tenant_id=? and template_code='P005_NOTICE_EVENT' and not is_deleted)
                """,
                tenantId,
                "{\"type\":\"object\",\"properties\":{\"businessNo\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"nodeLabel\":{\"type\":\"string\"}},\"required\":[\"businessNo\",\"event\",\"nodeLabel\"]}",
                tenantId);
    }

    private JsonNode parse(String raw) {
        try {
            JsonNode value = mapper.readTree(raw);
            if (value == null || !value.isObject())
                throw new IllegalArgumentException("P005 event payload must be an object");
            return value;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("P005 event payload is invalid JSON", ex);
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("P005 event field is required: " + field);
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
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("P005 notification recipient is not a UUID", ex);
            }
        });
        return result;
    }

    private static String nodeLabel(String node) {
        return switch (node) {
            case "S01" -> "制度/通知版本发布";
            case "S02" -> "按组织岗位确定范围";
            case "S03" -> "消息送达";
            case "S04" -> "员工阅读";
            case "S05" -> "确认/阅签";
            case "S06" -> "考试或理解验证";
            case "S07" -> "执行任务";
            case "S08" -> "责任人验收";
            case "S09" -> "未完成催办升级";
            case "S10" -> "档案移交";
            case "END" -> "已关闭";
            default -> throw new IllegalArgumentException("P005 event node is not source-backed: " + node);
        };
    }
}
