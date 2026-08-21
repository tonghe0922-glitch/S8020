-- PHASE-11 / P012: canonical promotion lifecycle and exactly-once appointment effect.
SET ROLE sjg_owner;

ALTER TABLE hr.promotion_request
  ADD COLUMN IF NOT EXISTS current_node_code varchar(16) DEFAULT 'S01' NOT NULL,
  ADD COLUMN IF NOT EXISTS fact_occurred_at timestamptz,
  ADD COLUMN IF NOT EXISTS fact_summary text,
  ADD COLUMN IF NOT EXISTS source_performance_cycle_id uuid,
  ADD COLUMN IF NOT EXISTS current_position_id uuid,
  ADD COLUMN IF NOT EXISTS target_position_id uuid,
  ADD COLUMN IF NOT EXISTS fsm_state varchar(32),
  ADD COLUMN IF NOT EXISTS timebox_state varchar(32),
  ADD COLUMN IF NOT EXISTS qa_state varchar(32),
  ADD COLUMN IF NOT EXISTS review_facet_count integer,
  ADD COLUMN IF NOT EXISTS weighted_review_score bigint,
  ADD COLUMN IF NOT EXISTS promotion_threshold_score bigint,
  ADD COLUMN IF NOT EXISTS content_version varchar(64),
  ADD COLUMN IF NOT EXISTS nomination_summary text,
  ADD COLUMN IF NOT EXISTS eligibility_verified_at timestamptz,
  ADD COLUMN IF NOT EXISTS assessment_summary text,
  ADD COLUMN IF NOT EXISTS position_budget_verified_at timestamptz,
  ADD COLUMN IF NOT EXISTS review_summary text,
  ADD COLUMN IF NOT EXISTS approval_decision text,
  ADD COLUMN IF NOT EXISTS approved_at timestamptz,
  ADD COLUMN IF NOT EXISTS notice_completed_at timestamptz,
  ADD COLUMN IF NOT EXISTS employee_confirmed_at timestamptz,
  ADD COLUMN IF NOT EXISTS validation_completed_at timestamptz,
  ADD COLUMN IF NOT EXISTS appointment_activated_at timestamptz,
  ADD COLUMN IF NOT EXISTS appointment_effect_id uuid,
  ADD COLUMN IF NOT EXISTS appointment_position_id uuid,
  ADD COLUMN IF NOT EXISTS ceo_mode boolean DEFAULT false NOT NULL;

ALTER TABLE org.employee_position
  ADD COLUMN IF NOT EXISTS source_promotion_request_id uuid;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p012_current_node'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT ck_p012_current_node CHECK (
        current_node_code IN ('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','END'))
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p012_review_scores'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT ck_p012_review_scores CHECK (
        review_facet_count IS NULL OR review_facet_count >= 1)
      NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p012_score_range'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT ck_p012_score_range CHECK (
        weighted_review_score IS NULL OR weighted_review_score BETWEEN 0 AND 1000)
      NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p012_threshold_range'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT ck_p012_threshold_range CHECK (
        promotion_threshold_score IS NULL OR promotion_threshold_score BETWEEN 0 AND 1000)
      NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='fk_p012_performance_cycle'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT fk_p012_performance_cycle
      FOREIGN KEY (source_performance_cycle_id) REFERENCES performance.performance_cycle(id)
      NOT VALID
    $ddl$;
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='fk_p012_target_position'
      AND n.nspname='hr' AND t.relname='promotion_request'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE hr.promotion_request
      ADD CONSTRAINT fk_p012_target_position
      FOREIGN KEY (target_position_id) REFERENCES org.position(id)
      NOT VALID
    $ddl$;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_p012_business_no
  ON hr.promotion_request(tenant_id,business_no) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p012_source_performance_cycle
  ON hr.promotion_request(tenant_id,source_performance_cycle_id)
  WHERE source_performance_cycle_id IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p012_candidate
  ON hr.promotion_request(tenant_id,owner_employee_id,created_at DESC) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_position_promotion_effect
  ON org.employee_position(tenant_id,source_promotion_request_id)
  WHERE source_promotion_request_id IS NOT NULL AND NOT is_deleted;

ALTER TABLE hr.promotion_request ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_promotion_request ON hr.promotion_request;
CREATE POLICY p_tenant_promotion_request ON hr.promotion_request
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P012','P012-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P012' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p012.promotion.create','P012晋升提名创建','CREATE','HIGH'),
  ('p012.promotion.read','P012晋升读取与本人确认','READ','NORMAL'),
  ('p012.promotion.review','P012晋升资格与评审','REVIEW','HIGH'),
  ('p012.promotion.appoint','P012晋升任命审批','APPOINT','HIGH'),
  ('p012.promotion.activate','P012任职正式生效','ACTIVATE','CRITICAL'),
  ('p012.promotion.monitor','P012晋升流程监控','MONITOR','NORMAL')
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
    AND process_code='P012' AND enabled AND NOT is_deleted
  ORDER BY created_at,id LIMIT 1;

  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      target_definition_id,'${sjg_tenant_id}'::uuid,'P012','晋升与任职发展',
      '绩效成长福利','hr','promotion_request',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_version
      WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id
        AND status='PUBLISHED' AND checksum='phase11-p012-c0-v1' AND NOT is_deleted) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,
      created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,
      coalesce(max(version_no),0)+1,'DRAFT',
      '{"processCode":"P012","source":"PHASE11_C0_CONTRACT","states":["S01","S02","S03","S04","S05","S06","S07","S08","S09","S10","END"]}'::jsonb,
      'phase11-p012-c0-v1',now(),now(),false
    FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,
      created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','提名提交','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','资格校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','评审资料与评价','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','岗位编制与预算核验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','评审会','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','公示与沟通','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','员工确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds","allowInitiator":true}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','任前校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','正式生效','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已关闭','END',NULL,110,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,
      condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','SUBMIT_NOMINATION','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','PASS_ELIGIBILITY','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','SUBMIT_ASSESSMENT','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','VERIFY_POSITION_BUDGET','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','COMPLETE_REVIEW','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','APPROVE_PROMOTION','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','COMPLETE_NOTICE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','CONFIRM_APPOINTMENT','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','COMPLETE_VALIDATION','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','ACTIVATE_APPOINTMENT','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version
    SET status='PUBLISHED',effective_at=now(),updated_at=now()
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_form_definition
      WHERE tenant_id='${sjg_tenant_id}'::uuid
        AND form_code='EMP-P012-F01' AND process_code='P012'
        AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'EMP-P012-F01',
      '晋升与任职发展-提名登记单','P012','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"period_no":{"type":"string"},"content_version":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary","period_no","content_version"]}'::jsonb,
      '{"sections":["晋升提名","绩效资格","目标任职"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","current_node_code","version_no","weighted_review_score","appointment_effect_id"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","reason","fact_summary"],"center":["subject","reason","fact_summary","period_no","content_version"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
