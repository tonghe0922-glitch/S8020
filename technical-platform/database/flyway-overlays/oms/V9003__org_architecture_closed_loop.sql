-- Organization architecture closed loop: technical direct maintenance, work draft/review/publish, and employee directory.
-- Additive only. Existing V35 organization tables remain the single published source of truth.
SET ROLE sjg_owner;

ALTER TABLE org.organization ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE org.organization ADD COLUMN IF NOT EXISTS headcount_plan integer DEFAULT 0 NOT NULL;
ALTER TABLE org.organization ADD COLUMN IF NOT EXISTS sort_no integer DEFAULT 0 NOT NULL;
ALTER TABLE org.organization ADD COLUMN IF NOT EXISTS version_no bigint DEFAULT 0 NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_org_organization_headcount_plan') THEN
        ALTER TABLE org.organization ADD CONSTRAINT ck_org_organization_headcount_plan CHECK (headcount_plan >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_org_organization_version_no') THEN
        ALTER TABLE org.organization ADD CONSTRAINT ck_org_organization_version_no CHECK (version_no >= 0);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_org_organization_code
    ON org.organization(tenant_id,org_code) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_org_organization_tree
    ON org.organization(tenant_id,parent_id,sort_no,org_code) WHERE NOT is_deleted;

CREATE TABLE IF NOT EXISTS org.architecture_draft (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_by uuid NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamptz,
    title varchar(160) NOT NULL,
    status varchar(24) DEFAULT 'DRAFT' NOT NULL,
    base_version bigint DEFAULT 0 NOT NULL,
    version_no bigint DEFAULT 0 NOT NULL,
    snapshot jsonb DEFAULT '[]'::jsonb NOT NULL,
    change_summary jsonb DEFAULT '[]'::jsonb NOT NULL,
    submitted_by uuid,
    submitted_at timestamptz,
    reviewed_by uuid,
    reviewed_at timestamptz,
    review_comment text,
    published_by uuid,
    published_at timestamptz,
    publish_key varchar(160),
    abandoned_by uuid,
    abandoned_at timestamptz,
    CONSTRAINT ck_org_architecture_draft_title CHECK (btrim(title)<>''),
    CONSTRAINT ck_org_architecture_draft_status CHECK (
        status IN ('DRAFT','PENDING','APPROVED','REJECTED','PUBLISHED','ABANDONED')),
    CONSTRAINT ck_org_architecture_draft_version CHECK (base_version>=0 AND version_no>=0),
    CONSTRAINT ck_org_architecture_draft_snapshot CHECK (jsonb_typeof(snapshot)='array'),
    CONSTRAINT ck_org_architecture_draft_changes CHECK (jsonb_typeof(change_summary)='array')
);
COMMENT ON TABLE org.architecture_draft IS '组织架构服务端草稿｜草稿、审批和发布状态机；正式组织仍以 org.organization 为唯一事实源';

CREATE TABLE IF NOT EXISTS org.architecture_version (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version_no bigint NOT NULL,
    source_draft_id uuid,
    snapshot jsonb DEFAULT '[]'::jsonb NOT NULL,
    change_summary jsonb DEFAULT '[]'::jsonb NOT NULL,
    published_by uuid,
    published_at timestamptz DEFAULT now() NOT NULL,
    CONSTRAINT ck_org_architecture_version_no CHECK (version_no>0),
    CONSTRAINT ck_org_architecture_version_snapshot CHECK (jsonb_typeof(snapshot)='array'),
    CONSTRAINT ck_org_architecture_version_changes CHECK (jsonb_typeof(change_summary)='array')
);
COMMENT ON TABLE org.architecture_version IS '组织架构发布版本｜用于乐观锁、差异追溯和跨端更新时间展示';

CREATE TABLE IF NOT EXISTS org.architecture_change (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    draft_id uuid,
    resource_id uuid,
    action_code varchar(64) NOT NULL,
    idempotency_key varchar(160),
    before_json jsonb,
    after_json jsonb,
    response_json jsonb,
    CONSTRAINT ck_org_architecture_change_action CHECK (btrim(action_code)<>'')
);
COMMENT ON TABLE org.architecture_change IS '组织架构变更与幂等回执｜保存直接配置和草稿流转的前后快照';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_architecture_draft_tenant') THEN
        ALTER TABLE org.architecture_draft ADD CONSTRAINT fk_org_architecture_draft_tenant
            FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.architecture_draft VALIDATE CONSTRAINT fk_org_architecture_draft_tenant;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_architecture_version_tenant') THEN
        ALTER TABLE org.architecture_version ADD CONSTRAINT fk_org_architecture_version_tenant
            FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.architecture_version VALIDATE CONSTRAINT fk_org_architecture_version_tenant;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_architecture_version_draft') THEN
        ALTER TABLE org.architecture_version ADD CONSTRAINT fk_org_architecture_version_draft
            FOREIGN KEY (source_draft_id) REFERENCES org.architecture_draft(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.architecture_version VALIDATE CONSTRAINT fk_org_architecture_version_draft;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_architecture_change_tenant') THEN
        ALTER TABLE org.architecture_change ADD CONSTRAINT fk_org_architecture_change_tenant
            FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.architecture_change VALIDATE CONSTRAINT fk_org_architecture_change_tenant;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_architecture_change_draft') THEN
        ALTER TABLE org.architecture_change ADD CONSTRAINT fk_org_architecture_change_draft
            FOREIGN KEY (draft_id) REFERENCES org.architecture_draft(id) ON DELETE RESTRICT NOT VALID;
        ALTER TABLE org.architecture_change VALIDATE CONSTRAINT fk_org_architecture_change_draft;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_org_architecture_version
    ON org.architecture_version(tenant_id,version_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_architecture_version_source_draft
    ON org.architecture_version(tenant_id,source_draft_id) WHERE source_draft_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_org_architecture_draft_actor_status
    ON org.architecture_draft(tenant_id,created_by,status,updated_at DESC) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_architecture_draft_publish_key
    ON org.architecture_draft(tenant_id,publish_key) WHERE publish_key IS NOT NULL AND NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_architecture_change_idempotency
    ON org.architecture_change(tenant_id,action_code,idempotency_key)
    WHERE idempotency_key IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_org_architecture_change_resource
    ON org.architecture_change(tenant_id,resource_id,created_at DESC) WHERE NOT is_deleted;

ALTER TABLE org.architecture_draft ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_org_architecture_draft ON org.architecture_draft;
CREATE POLICY p_tenant_org_architecture_draft ON org.architecture_draft
    USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE org.architecture_version ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_org_architecture_version ON org.architecture_version;
CREATE POLICY p_tenant_org_architecture_version ON org.architecture_version
    USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE org.architecture_change ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_org_architecture_change ON org.architecture_change;
CREATE POLICY p_tenant_org_architecture_change ON org.architecture_change
    USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
    WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON org.architecture_draft,org.architecture_version,org.architecture_change
    TO sjg_api_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON org.organization TO sjg_api_runtime;

INSERT INTO iam.permission(
    id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,
    created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'ORG_ARCHITECTURE',v.action,v.risk,
       now(),now(),false
FROM (VALUES
  ('org.architecture.view','企业架构查看','READ','NORMAL'),
  ('org.architecture.edit','企业架构草稿维护','EDIT','HIGH'),
  ('org.architecture.review','企业架构审批','REVIEW','HIGH'),
  ('org.architecture.publish','企业架构发布','PUBLISH','CRITICAL'),
  ('org.architecture.manage','企业架构技术配置','MANAGE','CRITICAL')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (
    SELECT 1 FROM iam.permission p
    WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

INSERT INTO iam.module(
    id,tenant_id,module_code,module_name,module_group,process_codes,sort_no,icon,enabled,remark,
    created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'ORG_ARCHITECTURE','组织架构管理','平台公共',
       '[]'::jsonb,15,'organization',true,'组织单一事实源、草稿审批发布与全员通讯录',
       now(),now(),false
WHERE NOT EXISTS (
    SELECT 1 FROM iam.module m
    WHERE m.tenant_id='${sjg_tenant_id}'::uuid AND m.module_code='ORG_ARCHITECTURE' AND NOT m.is_deleted);

INSERT INTO iam.module_permission(
    id,tenant_id,module_id,permission_id,capability_type,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),p.tenant_id,m.id,p.id,
       CASE p.action_code
         WHEN 'READ' THEN 'VIEW'
         WHEN 'REVIEW' THEN 'APPROVE'
         WHEN 'PUBLISH' THEN 'ADMIN'
         WHEN 'MANAGE' THEN 'ADMIN'
         ELSE 'OPERATE'
       END,
       now(),now(),false
FROM iam.permission p
JOIN iam.module m ON m.tenant_id=p.tenant_id AND m.module_code='ORG_ARCHITECTURE' AND NOT m.is_deleted
WHERE p.tenant_id='${sjg_tenant_id}'::uuid
  AND p.permission_code LIKE 'org.architecture.%'
  AND NOT p.is_deleted
  AND NOT EXISTS (
      SELECT 1 FROM iam.module_permission mp
      WHERE mp.tenant_id=p.tenant_id AND mp.module_id=m.id AND mp.permission_id=p.id AND NOT mp.is_deleted);

-- The controlled first administrator must be able to configure and validate this module on a fresh deployment.
INSERT INTO iam.role_permission(id,tenant_id,role_id,permission_id,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),r.tenant_id,r.id,p.id,now(),now(),false
FROM iam.role r
JOIN iam.permission p ON p.tenant_id=r.tenant_id AND NOT p.is_deleted
WHERE r.tenant_id='${sjg_tenant_id}'::uuid
  AND r.role_code='S8020_BOOTSTRAP_ADMIN'
  AND NOT r.is_deleted
  AND p.permission_code LIKE 'org.architecture.%'
  AND NOT EXISTS (
      SELECT 1 FROM iam.role_permission rp
      WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND rp.permission_id=p.id AND NOT rp.is_deleted);

-- Establish a version baseline from whatever organization rows already exist; no real employee names are seeded here.
INSERT INTO org.architecture_version(tenant_id,version_no,snapshot,change_summary,published_at)
SELECT '${sjg_tenant_id}'::uuid,1,
       COALESCE(jsonb_agg(jsonb_build_object(
           'id',o.id,
           'orgCode',o.org_code,
           'orgName',o.org_name,
           'orgType',o.org_type,
           'parentId',o.parent_id,
           'path',o.path::text,
           'status',o.status,
           'managerEmployeeId',o.manager_employee_id,
           'memberCount',0,
           'headcountPlan',o.headcount_plan,
           'sortNo',o.sort_no,
           'versionNo',o.version_no,
           'description',o.description
       ) ORDER BY o.path,o.sort_no,o.org_code),'[]'::jsonb),
       '[{"kind":"BASELINE","summary":"V9003 organization architecture baseline"}]'::jsonb,
       now()
FROM org.organization o
WHERE o.tenant_id='${sjg_tenant_id}'::uuid AND NOT o.is_deleted
HAVING NOT EXISTS (
    SELECT 1 FROM org.architecture_version v WHERE v.tenant_id='${sjg_tenant_id}'::uuid);

RESET ROLE;
