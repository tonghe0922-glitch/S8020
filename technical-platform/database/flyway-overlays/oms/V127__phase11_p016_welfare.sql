-- PHASE-11 / P016: welfare care adapter over the pre-existing PHASE-05 kernel.
-- V44 remains the canonical stable business-fact table; this overlay is additive only.
SET ROLE sjg_owner;

ALTER TABLE welfare.care_case
  ADD COLUMN IF NOT EXISTS current_node_code varchar(16);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p016_current_node' AND n.nspname='welfare' AND t.relname='care_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE welfare.care_case
      ADD CONSTRAINT ck_p016_current_node CHECK (
        employee_event_type<>'P016_CARE'
        OR current_node_code IN ('S01','S02','S03','S04','S05','S06','S07','S08','END')) NOT VALID
    $ddl$;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_p016_business_no
  ON welfare.care_case(tenant_id,business_no) WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p016_owner_case
  ON welfare.care_case(tenant_id,owner_center_id,owner_employee_id,created_at DESC)
  WHERE employee_event_type='P016_CARE' AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p016_workflow_case
  ON welfare.care_case(tenant_id,workflow_instance_id)
  WHERE employee_event_type='P016_CARE' AND workflow_instance_id IS NOT NULL AND NOT is_deleted;

CREATE TABLE IF NOT EXISTS welfare.care_case_fact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  care_case_id uuid NOT NULL REFERENCES welfare.care_case(id),
  fact_type varchar(32) NOT NULL,
  summary text NOT NULL,
  evidence_reference text,
  actor_employee_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_p016_fact_type CHECK (fact_type IN (
    'ELIGIBILITY_VERIFIED','PRIVACY_AUTHORIZED','CARE_APPROVED','BENEFIT_EXECUTED',
    'RECEIPT_CONFIRMED','RECONCILED','ARCHIVED')),
  CONSTRAINT ck_p016_fact_summary CHECK (btrim(summary)<>'')
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_p016_case_fact
  ON welfare.care_case_fact(tenant_id,care_case_id,fact_type);
CREATE INDEX IF NOT EXISTS ix_p016_case_fact_timeline
  ON welfare.care_case_fact(tenant_id,care_case_id,occurred_at,id);

ALTER TABLE welfare.care_case ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_care_case ON welfare.care_case;
CREATE POLICY p_tenant_care_case ON welfare.care_case
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

ALTER TABLE welfare.care_case_fact ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_care_case_fact ON welfare.care_case_fact;
CREATE POLICY p_tenant_care_case_fact ON welfare.care_case_fact
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P016','P016-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P016' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p016.care.create','P016福利关怀登记','CREATE','HIGH'),
  ('p016.care.read','P016福利关怀读取','READ','HIGH'),
  ('p016.care.review','P016资格与关怀审批','REVIEW','CRITICAL'),
  ('p016.care.execute','P016福利执行','EXECUTE','CRITICAL'),
  ('p016.care.confirm','P016员工隐私与回执确认','CONFIRM','HIGH'),
  ('p016.care.reconcile','P016福利对账','RECONCILE','CRITICAL'),
  ('p016.care.monitor','P016福利流程监控','MONITOR','NORMAL')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (
  SELECT 1 FROM iam.permission p
  WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE
  target_definition_id uuid;
  target_version_id uuid;
BEGIN
  SELECT id INTO target_definition_id
  FROM workflow.wf_definition
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P016' AND enabled AND NOT is_deleted
  ORDER BY created_at,id LIMIT 1;

  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(
      target_definition_id,'${sjg_tenant_id}'::uuid,'P016','福利关怀与台账',
      '绩效成长福利','welfare','care_case',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid
      AND definition_id=target_definition_id AND status='PUBLISHED'
      AND checksum='phase11-p016-c0-v1' AND NOT is_deleted
  ) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,coalesce(max(version_no),0)+1,'DRAFT',
      '{"processCode":"P016","source":"PHASE11_WORKFLOW_CONTRACT","reuseKernel":"PHASE05_WELFARE","nodes":["S01","S02","S03","S04","S05","S06","S07","S08","END"]}'::jsonb,
      'phase11-p016-c0-v1',now(),now(),false
    FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','关怀事项登记','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','资格核验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','隐私授权','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','关怀审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','福利执行','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','员工确认回执','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','对账','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已关闭','END',NULL,90,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','REGISTER_CARE_CASE','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','VERIFY_ELIGIBILITY','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','AUTHORIZE_PRIVACY','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','APPROVE_CARE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','EXECUTE_BENEFIT','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','CONFIRM_RECEIPT','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','RECONCILE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','ARCHIVE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version
    SET status='PUBLISHED',effective_at=now(),updated_at=now()
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM workflow.wf_form_definition
    WHERE tenant_id='${sjg_tenant_id}'::uuid
      AND form_code='EMP-P016-F01' AND process_code='P016' AND node_code='S01'
      AND enabled AND NOT is_deleted
  ) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'EMP-P016-F01','福利关怀与台账-关怀事项登记','P016','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"benefit_amount":{"type":"number"},"budget_item_id":{"type":"string"},"cost_center_id":{"type":"string"},"currency":{"type":"string"},"impact_level":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary"]}'::jsonb,
      '{"sections":["关怀事项","资格与隐私","福利执行与回执"]}'::jsonb,
      '{"serverAuthoritative":["workflow_state","eligibility_fact","privacy_authorization","execution_fact","receipt_fact","reconciliation_fact"]}'::jsonb,
      '{"employee":"SELF","center":"CENTER","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","reason","fact_summary"],"center":["subject","reason","owner_employee_id","owner_center_id","fact_summary","benefit_amount","budget_item_id","cost_center_id","currency","impact_level"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
