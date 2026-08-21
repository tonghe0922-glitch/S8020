SET ROLE sjg_owner;
SET search_path TO iam, notification, workflow, integration, core, public;

-- V107 is immutable once released. P002 asynchronous expiry hardening is additive here.
ALTER TABLE iam.permission_request_grant
    ALTER COLUMN updated_by DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS revoke_source varchar(32),
    ADD COLUMN IF NOT EXISTS expiry_retry_count integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS expiry_next_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS expiry_last_error text,
    ADD COLUMN IF NOT EXISTS expiry_dead_lettered_at timestamptz;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_permission_request_grant_revoke_source'
          AND conrelid = 'iam.permission_request_grant'::regclass
    ) THEN
        ALTER TABLE iam.permission_request_grant
            ADD CONSTRAINT ck_permission_request_grant_revoke_source
            CHECK (revoke_source IS NULL OR revoke_source IN ('MANUAL','AUTO_EXPIRE'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_permission_request_grant_expiry_retry_count'
          AND conrelid = 'iam.permission_request_grant'::regclass
    ) THEN
        ALTER TABLE iam.permission_request_grant
            ADD CONSTRAINT ck_permission_request_grant_expiry_retry_count
            CHECK (expiry_retry_count >= 0);
    END IF;
END $$;

-- permission_request_grant is an execution linkage table introduced by V107 and intentionally
-- has no soft-delete column. Expiry eligibility is therefore defined only by authoritative grant
-- state plus DLQ/retry facts; referencing is_deleted here would make the migration invalid.
CREATE INDEX IF NOT EXISTS ix_permission_request_grant_expiry_due
    ON iam.permission_request_grant(tenant_id,effective_end_at,expiry_next_attempt_at,permission_request_id)
    WHERE grant_status='ACTIVE' AND expiry_dead_lettered_at IS NULL;

COMMENT ON COLUMN iam.permission_request_grant.revoke_source IS
    '回收来源｜MANUAL人工回收 / AUTO_EXPIRE系统到期回收；系统回收不得伪造员工操作人';
COMMENT ON COLUMN iam.permission_request_grant.expiry_retry_count IS
    '到期回收重试次数｜worker权威事实';
COMMENT ON COLUMN iam.permission_request_grant.expiry_next_attempt_at IS
    '到期回收下次重试时间｜指数退避';
COMMENT ON COLUMN iam.permission_request_grant.expiry_last_error IS
    '到期回收最近错误｜不得包含密码/MFA等秘密';
COMMENT ON COLUMN iam.permission_request_grant.expiry_dead_lettered_at IS
    '到期回收进入DLQ时间｜需人工接管';

-- In-app notification template used by the P002 business-event handler. The message row itself is
-- the in-app mailbox fact; external channels remain separate providers.
INSERT INTO notification.template(
    id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled)
SELECT gen_random_uuid(),t.id,'P002_PERMISSION_EVENT','IN_APP',
       '权限申请进度：{{nodeLabel}}',
       '权限申请 {{businessNo}} 当前状态：{{nodeLabel}}（事件 {{event}}）。',
       '{"type":"object","properties":{"businessNo":{"type":"string"},"event":{"type":"string"},"nodeLabel":{"type":"string"}},"required":["businessNo","event","nodeLabel"]}'::jsonb,
       true
FROM core.tenant t
WHERE t.status='ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM notification.template n
      WHERE n.tenant_id=t.id AND n.template_code='P002_PERMISSION_EVENT' AND NOT n.is_deleted
  );

RESET ROLE;
