-- PHASE-06 C5 minimal platform overlay for provider request reference + inbound webhook evidence.
-- G06-12 records that approved V26 Integration DDL has request_log but no provider_event/webhook fact.

SET ROLE sjg_owner;

ALTER TABLE integration.request_log
    ADD COLUMN provider_reference varchar(128);

COMMENT ON COLUMN integration.request_log.provider_reference IS
    '外部Provider接受/请求参考号｜PHASE-06 C5工程证据；不等同最终送达/业务成功';

CREATE UNIQUE INDEX IF NOT EXISTS uk_integration_endpoint_tenant_id_id
    ON integration.endpoint (tenant_id, id);

CREATE TABLE integration.webhook_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    endpoint_id uuid NOT NULL,
    provider_event_id varchar(128) NOT NULL,
    event_type varchar(128) NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    signature_valid boolean NOT NULL,
    processing_status varchar(32) NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    error_code varchar(64),
    error_message text,
    CONSTRAINT fk_integration_webhook_event_endpoint
        FOREIGN KEY (tenant_id, endpoint_id) REFERENCES integration.endpoint(tenant_id, id),
    CONSTRAINT uk_integration_webhook_event_provider
        UNIQUE (tenant_id, endpoint_id, provider_event_id),
    CONSTRAINT ck_integration_webhook_event_status
        CHECK (processing_status IN ('RECEIVED','PROCESSED','REJECTED','FAILED'))
);

CREATE INDEX idx_integration_webhook_event_received
    ON integration.webhook_event (tenant_id, received_at DESC);
CREATE INDEX idx_integration_webhook_event_status
    ON integration.webhook_event (tenant_id, processing_status, received_at);

ALTER TABLE integration.webhook_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.webhook_event FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_integration_webhook_event ON integration.webhook_event;
CREATE POLICY p_tenant_integration_webhook_event ON integration.webhook_event
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- V99 default privileges give runtime DML on sjg_owner-created tables; webhook evidence is not runtime-deletable.
REVOKE DELETE ON integration.webhook_event FROM sjg_api_runtime, sjg_worker_runtime;

COMMENT ON TABLE integration.webhook_event IS
    '外部Webhook事件证据｜PHASE-06 C5最小工程overlay｜provider_event_id防重、签名结果、处理状态；非业务流程表';
COMMENT ON COLUMN integration.webhook_event.provider_event_id IS 'Provider原始事件ID｜同租户同endpoint唯一';
COMMENT ON COLUMN integration.webhook_event.payload_sha256 IS '原始JSON载荷SHA-256｜用于重复事件内容一致性校验';
COMMENT ON COLUMN integration.webhook_event.signature_valid IS 'Webhook签名验证结果｜false时REJECTED且不得进入下游Outbox';

RESET ROLE;
