-- PHASE-06 C6 minimal correlation/trace metadata for approved immutable sjg_audit tables.
-- No mutable audit substitute is introduced.

SET ROLE sjg_owner;

ALTER TABLE audit.access_log
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_audit_access_log_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));
ALTER TABLE audit.data_change_log
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_audit_data_change_log_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));
ALTER TABLE audit.operation_log
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_audit_operation_log_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));
ALTER TABLE audit.rule_execution_log
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_audit_rule_execution_log_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));
ALTER TABLE audit.security_event
    ADD COLUMN correlation_id varchar(128),
    ADD COLUMN trace_id varchar(128),
    ADD CONSTRAINT ck_audit_security_event_trace_pair CHECK ((correlation_id IS NULL) = (trace_id IS NULL));

CREATE INDEX idx_audit_access_log_trace ON audit.access_log (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_data_change_log_trace ON audit.data_change_log (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_operation_log_trace ON audit.operation_log (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_rule_execution_log_trace ON audit.rule_execution_log (tenant_id, trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_security_event_trace ON audit.security_event (tenant_id, trace_id) WHERE trace_id IS NOT NULL;

COMMENT ON COLUMN audit.operation_log.correlation_id IS '平台关联ID｜PHASE-06 C6 技术追踪元数据';
COMMENT ON COLUMN audit.operation_log.trace_id IS '平台Trace ID｜与Outbox/Inbox/Integration证据串联';
COMMENT ON COLUMN audit.access_log.correlation_id IS '平台关联ID｜敏感访问审计追踪';
COMMENT ON COLUMN audit.access_log.trace_id IS '平台Trace ID｜敏感访问审计追踪';
COMMENT ON COLUMN audit.security_event.correlation_id IS '平台关联ID｜安全事件审计追踪';
COMMENT ON COLUMN audit.security_event.trace_id IS '平台Trace ID｜安全事件审计追踪';

RESET ROLE;
