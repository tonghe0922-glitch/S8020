-- PHASE-11 / P015: immutable growth and honor point ledger.
SET ROLE sjg_owner;

ALTER TABLE reward.point_transaction
  ALTER COLUMN cost_center_id DROP NOT NULL,
  ALTER COLUMN currency DROP NOT NULL,
  ADD COLUMN IF NOT EXISTS source_fact_key varchar(160),
  ADD COLUMN IF NOT EXISTS source_type varchar(32),
  ADD COLUMN IF NOT EXISTS point_type varchar(32),
  ADD COLUMN IF NOT EXISTS rule_code varchar(64),
  ADD COLUMN IF NOT EXISTS rule_version varchar(64),
  ADD COLUMN IF NOT EXISTS calculation_snapshot jsonb,
  ADD COLUMN IF NOT EXISTS risk_class varchar(32),
  ADD COLUMN IF NOT EXISTS root_transaction_id uuid,
  ADD COLUMN IF NOT EXISTS reversal_of_id uuid,
  ADD COLUMN IF NOT EXISTS correction_evidence jsonb;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p015_legacy_finance_fields' AND n.nspname='reward' AND t.relname='point_transaction'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.point_transaction
      ADD CONSTRAINT ck_p015_legacy_finance_fields CHECK (
        employee_event_type='P015_POINTS' OR (cost_center_id IS NOT NULL AND currency IS NOT NULL)) NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p015_change_action' AND n.nspname='reward' AND t.relname='point_transaction'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.point_transaction
      ADD CONSTRAINT ck_p015_change_action CHECK (
        employee_event_type<>'P015_POINTS' OR change_action IN ('POST','ADJUST','REVERSAL')) NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p015_posting_facts' AND n.nspname='reward' AND t.relname='point_transaction'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.point_transaction
      ADD CONSTRAINT ck_p015_posting_facts CHECK (
        employee_event_type<>'P015_POINTS' OR change_action<>'POST' OR (
          source_fact_key IS NOT NULL AND btrim(source_fact_key)<>'' AND source_type IS NOT NULL
          AND point_type IS NOT NULL AND rule_code IS NOT NULL AND rule_version IS NOT NULL
          AND points_delta IS NOT NULL AND calculation_snapshot IS NOT NULL)) NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p015_correction_reference' AND n.nspname='reward' AND t.relname='point_transaction'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.point_transaction
      ADD CONSTRAINT ck_p015_correction_reference CHECK (
        employee_event_type<>'P015_POINTS' OR change_action='POST'
        OR (root_transaction_id IS NOT NULL AND reversal_of_id IS NOT NULL AND correction_evidence IS NOT NULL)) NOT VALID
    $ddl$;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_business_no
  ON reward.point_transaction(tenant_id,business_no) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_source_post
  ON reward.point_transaction(tenant_id,source_fact_key)
  WHERE employee_event_type='P015_POINTS' AND change_action='POST' AND source_fact_key IS NOT NULL AND NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_single_reversal
  ON reward.point_transaction(tenant_id,reversal_of_id)
  WHERE employee_event_type='P015_POINTS' AND change_action='REVERSAL' AND reversal_of_id IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p015_employee_ledger
  ON reward.point_transaction(tenant_id,owner_employee_id,point_type,created_at DESC)
  WHERE employee_event_type='P015_POINTS' AND NOT is_deleted;

CREATE OR REPLACE FUNCTION reward.fn_p015_point_transaction_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.employee_event_type='P015_POINTS' THEN
    RAISE EXCEPTION 'P015 point ledger is append-only; UPDATE/DELETE is forbidden'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_p015_point_transaction_immutable ON reward.point_transaction;
CREATE TRIGGER trg_p015_point_transaction_immutable
BEFORE UPDATE OR DELETE ON reward.point_transaction
FOR EACH ROW EXECUTE FUNCTION reward.fn_p015_point_transaction_immutable();

CREATE TABLE IF NOT EXISTS reward.point_rule_version (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  rule_code varchar(64) NOT NULL,
  version_code varchar(64) NOT NULL,
  source_type varchar(32) NOT NULL,
  point_type varchar(32) NOT NULL,
  points_delta bigint NOT NULL,
  review_threshold_abs bigint,
  status varchar(16) NOT NULL DEFAULT 'DRAFT',
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  rule_evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  is_deleted boolean NOT NULL DEFAULT false,
  CONSTRAINT ck_p015_rule_status CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  CONSTRAINT ck_p015_rule_window CHECK (effective_to IS NULL OR effective_to>effective_from),
  CONSTRAINT ck_p015_rule_threshold CHECK (review_threshold_abs IS NULL OR review_threshold_abs>=0)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_rule_version
  ON reward.point_rule_version(tenant_id,rule_code,version_code) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p015_rule_match
  ON reward.point_rule_version(tenant_id,rule_code,source_type,point_type,status,effective_from DESC)
  WHERE NOT is_deleted;

CREATE TABLE IF NOT EXISTS reward.point_source_guard (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  source_fact_key varchar(160) NOT NULL,
  point_case_id uuid NOT NULL,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_source_guard
  ON reward.point_source_guard(tenant_id,source_fact_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_p015_case_guard
  ON reward.point_source_guard(tenant_id,point_case_id);

CREATE TABLE IF NOT EXISTS reward.point_balance_snapshot (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  employee_id uuid NOT NULL,
  point_type varchar(32) NOT NULL,
  balance_points bigint NOT NULL,
  workflow_instance_id uuid NOT NULL,
  calculated_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid
);
CREATE INDEX IF NOT EXISTS ix_p015_balance_latest
  ON reward.point_balance_snapshot(tenant_id,employee_id,point_type,calculated_at DESC,id DESC);

ALTER TABLE reward.point_transaction ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_point_transaction ON reward.point_transaction;
CREATE POLICY p_tenant_point_transaction ON reward.point_transaction
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

ALTER TABLE reward.point_rule_version ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_point_rule_version ON reward.point_rule_version;
CREATE POLICY p_tenant_point_rule_version ON reward.point_rule_version
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE reward.point_source_guard ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_point_source_guard ON reward.point_source_guard;
CREATE POLICY p_tenant_point_source_guard ON reward.point_source_guard
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE reward.point_balance_snapshot ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_point_balance_snapshot ON reward.point_balance_snapshot;
CREATE POLICY p_tenant_point_balance_snapshot ON reward.point_balance_snapshot
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P015','P015-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P015' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p015.points.create','P015积分事件登记','CREATE','HIGH'),
  ('p015.points.read','P015积分流水读取','READ','HIGH'),
  ('p015.points.review','P015积分规则与入账复核','REVIEW','CRITICAL'),
  ('p015.points.reverse','P015积分调整冲销与重算','REVERSE','CRITICAL'),
  ('p015.points.monitor','P015积分运行监控','MONITOR','NORMAL')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (
  SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE
  target_definition_id uuid;
  target_version_id uuid;
BEGIN
  SELECT id INTO target_definition_id FROM workflow.wf_definition
   WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P015' AND enabled AND NOT is_deleted
   ORDER BY created_at,id LIMIT 1;
  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(target_definition_id,'${sjg_tenant_id}'::uuid,'P015','成长积分与荣誉积分',
      '绩效成长福利','reward','point_transaction',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid
      AND definition_id=target_definition_id AND status='PUBLISHED'
      AND checksum='phase11-p015-c0-v1' AND NOT is_deleted) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,coalesce(max(version_no),0)+1,'DRAFT',
      '{"processCode":"P015","source":"PHASE11_WORKFLOW_CONTRACT","appendOnlyLedger":true,"nodes":["S01","S02","S03","S04","S05","S06","S07","S08","S09","S10","END"]}'::jsonb,
      'phase11-p015-c0-v1',now(),now(),false
    FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','事件登记','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','来源校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','重复校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','规则版本匹配','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','积分计算','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','风险分类','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','入账或复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','员工通知','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','调整或冲销','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','余额重算','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已关闭','END',NULL,110,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','REGISTER_EVENT','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','VALIDATE_SOURCE','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','CHECK_DUPLICATE','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','MATCH_RULE_VERSION','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','CALCULATE_POINTS','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','CLASSIFY_RISK','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','POST_OR_REVIEW','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','NOTIFY_EMPLOYEE','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','ADJUST_OR_REVERSE','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','RECALCULATE_BALANCE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now()
     WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid
      AND form_code='CTR-P015-F01' AND process_code='P015' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P015-F01','成长与荣誉积分-事件登记单','P015','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"source_fact_key":{"type":"string"},"source_type":{"type":"string"},"point_type":{"type":"string"},"rule_code":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary","source_fact_key","source_type","point_type","rule_code"]}'::jsonb,
      '{"sections":["积分事件","来源证据","规则匹配"]}'::jsonb,
      '{"serverAuthoritative":["matched_rule_version","calculated_points","risk_class","points_delta","balance_points","workflow_state"]}'::jsonb,
      '{"employee":"SELF","center":"CENTER","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":[],"center":["subject","reason","owner_employee_id","owner_center_id","fact_summary","source_fact_key","source_type","point_type","rule_code"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
