-- ADR-006 module-based authorization configuration foundation.
-- Additive management-plane schema only. Runtime grant calculation remains unchanged until M3 reconciliation passes.
SET ROLE sjg_owner;

CREATE TABLE IF NOT EXISTS iam.module (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_by uuid,
    updated_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamptz,
    module_code varchar(64) NOT NULL,
    module_name varchar(128) NOT NULL,
    module_group varchar(64),
    parent_id uuid,
    process_codes jsonb DEFAULT '[]'::jsonb NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    icon varchar(64),
    enabled boolean DEFAULT true NOT NULL,
    remark text,
    CONSTRAINT ck_iam_module_process_codes_array CHECK (jsonb_typeof(process_codes)='array'),
    CONSTRAINT ck_iam_module_code_nonblank CHECK (btrim(module_code)<>''),
    CONSTRAINT ck_iam_module_name_nonblank CHECK (btrim(module_name)<>''),
    CONSTRAINT ck_iam_module_code_format CHECK (module_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);
COMMENT ON TABLE iam.module IS '功能模块表｜技术端可配置的最小业务能力单元；模块只分组和收窄权限，不形成授权旁路';

CREATE TABLE IF NOT EXISTS iam.module_permission (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_by uuid,
    updated_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamptz,
    module_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    capability_type varchar(16) DEFAULT 'OPERATE' NOT NULL,
    CONSTRAINT ck_iam_module_permission_capability CHECK (capability_type IN ('VIEW','OPERATE','APPROVE','ADMIN'))
);
COMMENT ON TABLE iam.module_permission IS '模块权限归属表｜声明权限点所属模块及 VIEW/OPERATE/APPROVE/ADMIN 能力档位';

CREATE TABLE IF NOT EXISTS iam.org_module (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_by uuid,
    updated_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamptz,
    org_id uuid NOT NULL,
    module_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    inherit_to_children boolean DEFAULT true NOT NULL,
    effective_start_at timestamptz DEFAULT now() NOT NULL,
    effective_end_at timestamptz,
    remark text,
    CONSTRAINT ck_iam_org_module_effective_range CHECK (effective_end_at IS NULL OR effective_end_at>effective_start_at)
);
COMMENT ON TABLE iam.org_module IS '组织模块启用表｜组织未启用的模块只能过滤权限，不能新增权限';

CREATE TABLE IF NOT EXISTS iam.position_role (
    id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_by uuid,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_by uuid,
    updated_at timestamptz DEFAULT now() NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamptz,
    position_id uuid NOT NULL,
    role_id uuid NOT NULL,
    effective_start_at timestamptz DEFAULT now() NOT NULL,
    effective_end_at timestamptz,
    grant_source varchar(32) DEFAULT 'POSITION_CONFIG' NOT NULL,
    CONSTRAINT ck_iam_position_role_effective_range CHECK (effective_end_at IS NULL OR effective_end_at>effective_start_at),
    CONSTRAINT ck_iam_position_role_source CHECK (btrim(grant_source)<>'')
);
COMMENT ON TABLE iam.position_role IS '岗位角色表｜岗位标准授权通道；与 user_role 并存，运行时启用受 M3 对账门禁约束';

CREATE OR REPLACE FUNCTION iam.enforce_module_process_codes() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, iam, workflow AS $$
DECLARE candidate text;
BEGIN
  FOR candidate IN SELECT jsonb_array_elements_text(NEW.process_codes) LOOP
    IF candidate !~ '^P[0-9]{3}$' THEN RAISE EXCEPTION 'invalid module process code: %', candidate USING ERRCODE='23514'; END IF;
    IF NOT EXISTS (SELECT 1 FROM workflow.wf_definition d WHERE d.tenant_id=NEW.tenant_id AND d.process_code=candidate AND d.enabled AND NOT d.is_deleted) THEN
      RAISE EXCEPTION 'module process code is not registered: %', candidate USING ERRCODE='23503';
    END IF;
  END LOOP;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION iam.enforce_module_process_codes() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_iam_module_process_codes ON iam.module;
CREATE TRIGGER trg_iam_module_process_codes BEFORE INSERT OR UPDATE OF tenant_id,process_codes ON iam.module FOR EACH ROW EXECUTE FUNCTION iam.enforce_module_process_codes();

CREATE OR REPLACE FUNCTION iam.enforce_module_admin_risk() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, iam AS $$
BEGIN
  IF NEW.capability_type='ADMIN' AND NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.permission_id AND p.risk_level IN ('HIGH','CRITICAL') AND NOT p.is_deleted) THEN
    RAISE EXCEPTION 'ADMIN capability requires a HIGH or CRITICAL permission' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION iam.enforce_module_admin_risk() FROM PUBLIC;
DROP TRIGGER IF EXISTS trg_iam_module_admin_risk ON iam.module_permission;
CREATE TRIGGER trg_iam_module_admin_risk BEFORE INSERT OR UPDATE OF tenant_id,permission_id,capability_type ON iam.module_permission FOR EACH ROW EXECUTE FUNCTION iam.enforce_module_admin_risk();

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_module_tenant_id_id ON iam.module(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_permission_tenant_id_id ON iam.permission(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_role_tenant_id_id ON iam.role(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_organization_tenant_id_id ON org.organization(tenant_id,id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_position_tenant_id_id ON org.position(tenant_id,id);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_module_tenant') THEN ALTER TABLE iam.module ADD CONSTRAINT fk_iam_module_tenant FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.module VALIDATE CONSTRAINT fk_iam_module_tenant; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_module_parent') THEN ALTER TABLE iam.module ADD CONSTRAINT fk_iam_module_parent FOREIGN KEY (tenant_id,parent_id) REFERENCES iam.module(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.module VALIDATE CONSTRAINT fk_iam_module_parent; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_module_permission_tenant') THEN ALTER TABLE iam.module_permission ADD CONSTRAINT fk_iam_module_permission_tenant FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.module_permission VALIDATE CONSTRAINT fk_iam_module_permission_tenant; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_module_permission_module') THEN ALTER TABLE iam.module_permission ADD CONSTRAINT fk_iam_module_permission_module FOREIGN KEY (tenant_id,module_id) REFERENCES iam.module(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.module_permission VALIDATE CONSTRAINT fk_iam_module_permission_module; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_module_permission_permission') THEN ALTER TABLE iam.module_permission ADD CONSTRAINT fk_iam_module_permission_permission FOREIGN KEY (tenant_id,permission_id) REFERENCES iam.permission(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.module_permission VALIDATE CONSTRAINT fk_iam_module_permission_permission; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_org_module_tenant') THEN ALTER TABLE iam.org_module ADD CONSTRAINT fk_iam_org_module_tenant FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.org_module VALIDATE CONSTRAINT fk_iam_org_module_tenant; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_org_module_org') THEN ALTER TABLE iam.org_module ADD CONSTRAINT fk_iam_org_module_org FOREIGN KEY (tenant_id,org_id) REFERENCES org.organization(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.org_module VALIDATE CONSTRAINT fk_iam_org_module_org; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_org_module_module') THEN ALTER TABLE iam.org_module ADD CONSTRAINT fk_iam_org_module_module FOREIGN KEY (tenant_id,module_id) REFERENCES iam.module(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.org_module VALIDATE CONSTRAINT fk_iam_org_module_module; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_position_role_tenant') THEN ALTER TABLE iam.position_role ADD CONSTRAINT fk_iam_position_role_tenant FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.position_role VALIDATE CONSTRAINT fk_iam_position_role_tenant; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_position_role_position') THEN ALTER TABLE iam.position_role ADD CONSTRAINT fk_iam_position_role_position FOREIGN KEY (tenant_id,position_id) REFERENCES org.position(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.position_role VALIDATE CONSTRAINT fk_iam_position_role_position; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_iam_position_role_role') THEN ALTER TABLE iam.position_role ADD CONSTRAINT fk_iam_position_role_role FOREIGN KEY (tenant_id,role_id) REFERENCES iam.role(tenant_id,id) ON DELETE RESTRICT NOT VALID; ALTER TABLE iam.position_role VALIDATE CONSTRAINT fk_iam_position_role_role; END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_module_code ON iam.module(tenant_id,module_code) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_iam_module_parent_sort ON iam.module(tenant_id,parent_id,sort_no,id) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_module_permission ON iam.module_permission(tenant_id,module_id,permission_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_iam_module_permission_permission ON iam.module_permission(tenant_id,permission_id,module_id) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_org_module ON iam.org_module(tenant_id,org_id,module_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_iam_org_module_enabled ON iam.org_module(tenant_id,org_id,module_id) WHERE NOT is_deleted AND enabled;
CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_position_role ON iam.position_role(tenant_id,position_id,role_id) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_iam_position_role_active ON iam.position_role(tenant_id,position_id,effective_start_at,effective_end_at) WHERE NOT is_deleted;

ALTER TABLE iam.module ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_iam_module ON iam.module;
CREATE POLICY p_tenant_iam_module ON iam.module USING (tenant_id=current_setting('app.tenant_id',true)::uuid) WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE iam.module_permission ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_iam_module_permission ON iam.module_permission;
CREATE POLICY p_tenant_iam_module_permission ON iam.module_permission USING (tenant_id=current_setting('app.tenant_id',true)::uuid) WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE iam.org_module ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_iam_org_module ON iam.org_module;
CREATE POLICY p_tenant_iam_org_module ON iam.org_module USING (tenant_id=current_setting('app.tenant_id',true)::uuid) WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE iam.position_role ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_iam_position_role ON iam.position_role;
CREATE POLICY p_tenant_iam_position_role ON iam.position_role USING (tenant_id=current_setting('app.tenant_id',true)::uuid) WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

GRANT SELECT,INSERT,UPDATE,DELETE ON iam.module,iam.module_permission,iam.org_module,iam.position_role TO sjg_api_runtime;
RESET ROLE;
