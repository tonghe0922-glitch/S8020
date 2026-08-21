-- PHASE-03 closes five catalogued audit tenant indexes omitted from the approved DDL package.
-- Source: Knowledge Base/03 数据库需求规则/02_数据字典/06_索引设计.csv
-- These are implementation overlays of explicit catalog facts, not invented index design.

SET ROLE sjg_owner;
CREATE INDEX IF NOT EXISTS idx_audit_operation_log_tenant ON audit.operation_log USING BTREE (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_data_change_log_tenant ON audit.data_change_log USING BTREE (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_access_log_tenant ON audit.access_log USING BTREE (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_rule_execution_log_tenant ON audit.rule_execution_log USING BTREE (tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_security_event_tenant ON audit.security_event USING BTREE (tenant_id);
RESET ROLE;
