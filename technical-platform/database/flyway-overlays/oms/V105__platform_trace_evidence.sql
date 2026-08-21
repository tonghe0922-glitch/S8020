-- PHASE-06 C6 minimal technical trace metadata overlay for durable async/provider evidence.
-- These columns carry platform correlation only; they do not add business workflow semantics.

SET ROLE sjg_owner;

ALTER TABLE core.outbox_event
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_core_outbox_event_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

ALTER TABLE core.inbox_event
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_core_inbox_event_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

ALTER TABLE integration.request_log
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_integration_request_log_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

ALTER TABLE integration.webhook_event
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_integration_webhook_event_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

ALTER TABLE integration.dead_letter
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_integration_dead_letter_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

CREATE INDEX idx_core_outbox_event_trace ON core.outbox_event (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_core_inbox_event_trace ON core.inbox_event (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_integration_request_log_trace ON integration.request_log (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_integration_webhook_event_trace ON integration.webhook_event (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_integration_dead_letter_trace ON integration.dead_letter (tenant_id, trace_id) WHERE trace_id IS NOT NULL;

COMMENT ON COLUMN core.outbox_event.correlation_id IS '平台关联ID｜PHASE-06 C6 技术追踪元数据';
COMMENT ON COLUMN core.outbox_event.trace_id IS '平台Trace ID｜持久化跨Worker恢复，不依赖ThreadLocal';
COMMENT ON COLUMN core.inbox_event.correlation_id IS '平台关联ID｜由消费时恢复的Outbox trace写入';
COMMENT ON COLUMN core.inbox_event.trace_id IS '平台Trace ID｜消费防重证据的一部分';
COMMENT ON COLUMN integration.request_log.correlation_id IS '平台关联ID｜外部请求证据';
COMMENT ON COLUMN integration.request_log.trace_id IS '平台Trace ID｜外部请求证据';
COMMENT ON COLUMN integration.webhook_event.correlation_id IS '平台关联ID｜Webhook入口证据';
COMMENT ON COLUMN integration.webhook_event.trace_id IS '平台Trace ID｜Webhook入口证据';
COMMENT ON COLUMN integration.dead_letter.correlation_id IS '平台关联ID｜死信恢复定位';
COMMENT ON COLUMN integration.dead_letter.trace_id IS '平台Trace ID｜死信恢复定位';

RESET ROLE;
