-- PHASE-11 / P011: performance lifecycle with independent score facts.
SET ROLE sjg_owner;

ALTER TABLE performance.performance_cycle
    ADD COLUMN IF NOT EXISTS current_node_code varchar(16),
    ADD COLUMN IF NOT EXISTS fact_summary text,
    ADD COLUMN IF NOT EXISTS goal_summary text,
    ADD COLUMN IF NOT EXISTS employee_confirmed_at timestamptz,
    ADD COLUMN IF NOT EXISTS coaching_summary text,
    ADD COLUMN IF NOT EXISTS authoritative_data_summary text,
    ADD COLUMN IF NOT EXISTS employee_score_1000 bigint,
    ADD COLUMN IF NOT EXISTS supervisor_score_1000 bigint,
    ADD COLUMN IF NOT EXISTS authoritative_score_1000 bigint,
    ADD COLUMN IF NOT EXISTS calibrated_score_1000 bigint,
    ADD COLUMN IF NOT EXISTS feedback_confirmed_at timestamptz,
    ADD COLUMN IF NOT EXISTS appeal_reason text,
    ADD COLUMN IF NOT EXISTS appeal_reviewer_id uuid,
    ADD COLUMN IF NOT EXISTS appeal_decision text,
    ADD COLUMN IF NOT EXISTS impact_executed_at timestamptz,
    ADD COLUMN IF NOT EXISTS archived_at timestamptz;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p011_current_node') THEN
    ALTER TABLE performance.performance_cycle
      ADD CONSTRAINT ck_p011_current_node CHECK (
        employee_event_type <> 'P011_PERFORMANCE'
        OR current_node_code IN ('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','S11','END'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p011_score_sources_1000') THEN
    ALTER TABLE performance.performance_cycle
      ADD CONSTRAINT ck_p011_score_sources_1000 CHECK (
        employee_event_type <> 'P011_PERFORMANCE'
        OR (
          (employee_score_1000 IS NULL OR employee_score_1000 BETWEEN 0 AND 1000)
          AND (supervisor_score_1000 IS NULL OR supervisor_score_1000 BETWEEN 0 AND 1000)
          AND (authoritative_score_1000 IS NULL OR authoritative_score_1000 BETWEEN 0 AND 1000)
          AND (calibrated_score_1000 IS NULL OR calibrated_score_1000 BETWEEN 0 AND 1000)
          AND (score_1000 IS NULL OR score_1000 BETWEEN 0 AND 1000)
        ));
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS performance.performance_score_entry (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    score_type varchar(32) NOT NULL,
    score_1000 bigint NOT NULL,
    source_fact_key varchar(160) NOT NULL,
    evidence_summary text NOT NULL,
    submitted_by uuid NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_performance_score_entry_type
      CHECK (score_type IN ('EMPLOYEE','SUPERVISOR','AUTHORITATIVE','CALIBRATED')),
    CONSTRAINT ck_performance_score_entry_1000 CHECK (score_1000 BETWEEN 0 AND 1000),
    CONSTRAINT fk_performance_score_entry_cycle
      FOREIGN KEY (cycle_id) REFERENCES performance.performance_cycle(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_performance_score_entry_type
  ON performance.performance_score_entry(tenant_id,cycle_id,score_type)
  WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_performance_score_entry_fact
  ON performance.performance_score_entry(tenant_id,source_fact_key)
  WHERE NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_performance_cycle_p011_owner
  ON performance.performance_cycle(tenant_id,owner_employee_id,created_at desc)
  WHERE employee_event_type='P011_PERFORMANCE' AND NOT is_deleted;

CREATE OR REPLACE FUNCTION performance.prevent_performance_score_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'P011 performance score facts are append-only';
END $$;
DROP TRIGGER IF EXISTS trg_performance_score_immutable ON performance.performance_score_entry;
CREATE TRIGGER trg_performance_score_immutable
BEFORE UPDATE OR DELETE ON performance.performance_score_entry
FOR EACH ROW EXECUTE FUNCTION performance.prevent_performance_score_mutation();

ALTER TABLE performance.performance_score_entry ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_performance_score_entry ON performance.performance_score_entry;
CREATE POLICY p_tenant_performance_score_entry ON performance.performance_score_entry
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P011','P011-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P011' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p011.performance.create','P011绩效周期创建','CREATE','NORMAL'),
  ('p011.performance.read','P011绩效读取','READ','NORMAL'),
  ('p011.performance.self','P011员工目标确认与自评','SELF','NORMAL'),
  ('p011.performance.evaluate','P011主管评价与事实归集','EVALUATE','HIGH'),
  ('p011.performance.calibrate','P011绩效校准','CALIBRATE','HIGH'),
  ('p011.performance.appeal','P011绩效申诉复核','APPEAL','HIGH'),
  ('p011.performance.impact','P011绩效影响执行','IMPACT','HIGH'),
  ('p011.performance.monitor','P011绩效运行监控','MONITOR','NORMAL')
) AS v(code,name,action,risk)
WHERE NOT EXISTS (
  SELECT 1 FROM iam.permission p
  WHERE p.tenant_id='${sjg_tenant_id}'::uuid
    AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE target_definition_id uuid; target_version_id uuid;
BEGIN
  SELECT id INTO target_definition_id
  FROM workflow.wf_definition
  WHERE tenant_id='${sjg_tenant_id}'::uuid
    AND process_code='P011' AND enabled AND NOT is_deleted
  ORDER BY created_at,id LIMIT 1;
  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      target_definition_id,'${sjg_tenant_id}'::uuid,'P011','绩效管理','绩效成长福利',
      'performance','performance_cycle',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_version
      WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id
        AND status='PUBLISHED' AND checksum='phase11-p011-c0-v1' AND NOT is_deleted) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,
      created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,
      coalesce(max(version_no),0)+1,'DRAFT',
      jsonb_build_object(
        'processCode','P011','source','PHASE11_C0_CONTRACT',
        'states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','S11','END')),
      'phase11-p011-c0-v1',now(),now(),false
    FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,
      created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','目标制定','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','员工确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds","allowInitiator":true}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','过程记录与辅导','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','权威数据归集','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','员工自评/主管评价','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','1000分计算','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','校准','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"calibratorCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','结果反馈确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds","allowInitiator":true}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','申诉复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"appealReviewerIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','绩效影响执行','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S11','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,110,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已关闭','END',NULL,120,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,
      condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','SET_TARGETS','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','CONFIRM_TARGETS','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','RECORD_COACHING','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','COLLECT_FACTS','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','SUBMIT_REVIEWS','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','CALCULATE_SCORE','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','CALIBRATE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','SUBMIT_APPEAL_DECISION','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','RESOLVE_APPEAL','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','EXECUTE_IMPACT','S11',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S11','ARCHIVE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version
    SET status='PUBLISHED',effective_at=now(),updated_at=now()
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_form_definition
      WHERE tenant_id='${sjg_tenant_id}'::uuid
        AND form_code='EMP-P011-F01' AND process_code='P011'
        AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'EMP-P011-F01',
      '绩效管理-目标制定-登记单','P011','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"period_no":{"type":"string"},"content_version":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary","period_no","content_version"]}'::jsonb,
      '{"sections":["绩效周期","目标与事实","责任员工"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","current_node_code","version_no","score_1000"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","reason","fact_summary"],"center":["subject","reason","fact_summary","period_no","content_version"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
