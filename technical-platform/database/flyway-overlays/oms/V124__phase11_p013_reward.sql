-- PHASE-11 / P013: reward recognition with evidence uniqueness and exactly-once point effect.
SET ROLE sjg_owner;

ALTER TABLE reward.reward_case
  ADD COLUMN IF NOT EXISTS current_node_code varchar(16) DEFAULT 'S01' NOT NULL,
  ADD COLUMN IF NOT EXISTS source_fact_key varchar(160),
  ADD COLUMN IF NOT EXISTS content_version varchar(64),
  ADD COLUMN IF NOT EXISTS period_no varchar(32),
  ADD COLUMN IF NOT EXISTS evidence_verified_at timestamptz,
  ADD COLUMN IF NOT EXISTS recommendation_summary text,
  ADD COLUMN IF NOT EXISTS approval_decision text,
  ADD COLUMN IF NOT EXISTS approved_at timestamptz,
  ADD COLUMN IF NOT EXISTS duplicate_checked_at timestamptz,
  ADD COLUMN IF NOT EXISTS finance_reference_id uuid,
  ADD COLUMN IF NOT EXISTS point_effect_id uuid,
  ADD COLUMN IF NOT EXISTS reward_executed_at timestamptz,
  ADD COLUMN IF NOT EXISTS employee_notified_at timestamptz,
  ADD COLUMN IF NOT EXISTS receipt_reference varchar(128),
  ADD COLUMN IF NOT EXISTS receipts_recorded_at timestamptz,
  ADD COLUMN IF NOT EXISTS archived_at timestamptz;

ALTER TABLE reward.point_transaction
  ADD COLUMN IF NOT EXISTS current_node_code varchar(16) DEFAULT 'S01' NOT NULL,
  ADD COLUMN IF NOT EXISTS source_fact_key varchar(160),
  ADD COLUMN IF NOT EXISTS source_reward_case_id uuid,
  ADD COLUMN IF NOT EXISTS content_version varchar(64),
  ADD COLUMN IF NOT EXISTS period_no varchar(32);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p013_current_node'
      AND n.nspname='reward' AND t.relname='reward_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.reward_case
      ADD CONSTRAINT ck_p013_current_node CHECK (
        employee_event_type <> 'P013_REWARD'
        OR current_node_code IN ('S01','S02','S03','S04','S05','S06','S07','S08','S09','END'))
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p013_benefit_non_negative'
      AND n.nspname='reward' AND t.relname='reward_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.reward_case
      ADD CONSTRAINT ck_p013_benefit_non_negative CHECK (
        employee_event_type <> 'P013_REWARD'
        OR coalesce(benefit_amount,0) >= 0)
      NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='fk_p013_point_reward_source'
      AND n.nspname='reward' AND t.relname='point_transaction'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.point_transaction
      ADD CONSTRAINT fk_p013_point_reward_source
      FOREIGN KEY (source_reward_case_id) REFERENCES reward.reward_case(id)
      NOT VALID
    $ddl$;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_p013_business_no
  ON reward.reward_case(tenant_id,business_no) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p013_source_fact
  ON reward.reward_case(tenant_id,source_fact_key)
  WHERE employee_event_type='P013_REWARD' AND source_fact_key IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p013_reward_recipient
  ON reward.reward_case(tenant_id,owner_employee_id,created_at DESC)
  WHERE employee_event_type='P013_REWARD' AND NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p013_point_effect
  ON reward.point_transaction(tenant_id,source_reward_case_id)
  WHERE source_reward_case_id IS NOT NULL AND NOT is_deleted;

ALTER TABLE reward.reward_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE reward.point_transaction ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_reward_case ON reward.reward_case;
CREATE POLICY p_tenant_reward_case ON reward.reward_case
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
DROP POLICY IF EXISTS p_tenant_point_transaction ON reward.point_transaction;
CREATE POLICY p_tenant_point_transaction ON reward.point_transaction
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P013','P013-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P013' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p013.reward.create','P013奖励事实登记','CREATE','NORMAL'),
  ('p013.reward.read','P013奖励读取','READ','NORMAL'),
  ('p013.reward.review','P013奖励证据复核与审批','REVIEW','HIGH'),
  ('p013.reward.execute','P013奖励执行与回执','EXECUTE','CRITICAL'),
  ('p013.reward.monitor','P013奖励流程监控','MONITOR','NORMAL')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (
  SELECT 1 FROM iam.permission p
  WHERE p.tenant_id='${sjg_tenant_id}'::uuid
    AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE
  target_definition_id uuid;
  target_version_id uuid;
BEGIN
  SELECT id INTO target_definition_id
  FROM workflow.wf_definition
  WHERE tenant_id='${sjg_tenant_id}'::uuid
    AND process_code='P013' AND enabled AND NOT is_deleted
  ORDER BY created_at,id LIMIT 1;

  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      target_definition_id,'${sjg_tenant_id}'::uuid,'P013','奖励与认可',
      '绩效成长福利','reward','reward_case',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_version
      WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id
        AND status='PUBLISHED' AND checksum='phase11-p013-c0-v1' AND NOT is_deleted) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,
      created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,
      coalesce(max(version_no),0)+1,'DRAFT',
      '{"processCode":"P013","source":"PHASE11_C0_CONTRACT","states":["S01","S02","S03","S04","S05","S06","S07","S08","S09","END"]}'::jsonb,
      'phase11-p013-c0-v1',now(),now(),false
    FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,
      created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','贡献事实登记','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','证据核验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','奖励建议','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','奖励审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','重复影响校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','奖励执行','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','员工告知','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','回执登记','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已关闭','END',NULL,100,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,
      condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','REGISTER_CONTRIBUTION','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','VERIFY_EVIDENCE','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','RECOMMEND_REWARD','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','APPROVE_REWARD','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','CHECK_DUPLICATE_IMPACT','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','EXECUTE_REWARD','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','NOTIFY_EMPLOYEE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','RECORD_RECEIPTS','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','ARCHIVE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version
    SET status='PUBLISHED',effective_at=now(),updated_at=now()
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_form_definition
      WHERE tenant_id='${sjg_tenant_id}'::uuid
        AND form_code='EMP-P013-F01' AND process_code='P013'
        AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'EMP-P013-F01',
      '奖励与认可-贡献事实登记单','P013','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"period_no":{"type":"string"},"content_version":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary","period_no","content_version"]}'::jsonb,
      '{"sections":["贡献事实","奖励影响","责任员工"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","current_node_code","version_no","point_effect_id","finance_reference_id"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","reason","fact_summary"],"center":["subject","reason","fact_summary","period_no","content_version"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
