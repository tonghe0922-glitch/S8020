-- PHASE-11 / P014: discipline responsibility, independent appeal and remediation.
SET ROLE sjg_owner;

ALTER TABLE reward.discipline_case
  ALTER COLUMN customer_id DROP NOT NULL,
  ALTER COLUMN customer_name DROP NOT NULL,
  ADD COLUMN IF NOT EXISTS current_node_code varchar(16) DEFAULT 'S01' NOT NULL,
  ADD COLUMN IF NOT EXISTS source_fact_key varchar(160),
  ADD COLUMN IF NOT EXISTS source_type varchar(32),
  ADD COLUMN IF NOT EXISTS content_version varchar(64),
  ADD COLUMN IF NOT EXISTS period_no varchar(32),
  ADD COLUMN IF NOT EXISTS safety_measure text,
  ADD COLUMN IF NOT EXISTS safety_evidence jsonb,
  ADD COLUMN IF NOT EXISTS safety_measure_at timestamptz,
  ADD COLUMN IF NOT EXISTS investigator_employee_id uuid,
  ADD COLUMN IF NOT EXISTS investigation_finding text,
  ADD COLUMN IF NOT EXISTS investigation_evidence jsonb,
  ADD COLUMN IF NOT EXISTS investigation_completed_at timestamptz,
  ADD COLUMN IF NOT EXISTS defense_statement text,
  ADD COLUMN IF NOT EXISTS defense_evidence jsonb,
  ADD COLUMN IF NOT EXISTS defense_submitted_at timestamptz,
  ADD COLUMN IF NOT EXISTS responsibility_reviewer_employee_id uuid,
  ADD COLUMN IF NOT EXISTS responsibility_review text,
  ADD COLUMN IF NOT EXISTS responsibility_reviewed_at timestamptz,
  ADD COLUMN IF NOT EXISTS decision_employee_id uuid,
  ADD COLUMN IF NOT EXISTS decision_summary text,
  ADD COLUMN IF NOT EXISTS decision_at timestamptz,
  ADD COLUMN IF NOT EXISTS service_proof jsonb,
  ADD COLUMN IF NOT EXISTS decision_served_at timestamptz,
  ADD COLUMN IF NOT EXISTS impact_summary text,
  ADD COLUMN IF NOT EXISTS impact_execution_evidence jsonb,
  ADD COLUMN IF NOT EXISTS impact_executed_at timestamptz,
  ADD COLUMN IF NOT EXISTS appeal_reviewer_employee_id uuid,
  ADD COLUMN IF NOT EXISTS appeal_result varchar(16),
  ADD COLUMN IF NOT EXISTS appeal_decision text,
  ADD COLUMN IF NOT EXISTS appeal_decision_evidence jsonb,
  ADD COLUMN IF NOT EXISTS appeal_resolved_at timestamptz,
  ADD COLUMN IF NOT EXISTS closure_summary text,
  ADD COLUMN IF NOT EXISTS core_closed_at timestamptz,
  ADD COLUMN IF NOT EXISTS remediation_summary text,
  ADD COLUMN IF NOT EXISTS observation_evidence jsonb,
  ADD COLUMN IF NOT EXISTS observation_completed_at timestamptz,
  ADD COLUMN IF NOT EXISTS archived_at timestamptz;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_current_node'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_current_node CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR current_node_code IN ('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','S11','S12','END'))
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_source_fact_required'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_source_fact_required CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR (source_fact_key IS NOT NULL AND btrim(source_fact_key) <> ''))
      NOT VALID
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_investigator_sod'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_investigator_sod CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR investigator_employee_id IS NULL
        OR owner_employee_id IS NULL
        OR investigator_employee_id <> owner_employee_id)
      NOT VALID
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_decision_sod'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_decision_sod CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR decision_employee_id IS NULL
        OR owner_employee_id IS NULL
        OR decision_employee_id <> owner_employee_id)
      NOT VALID
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_appeal_reviewer_sod'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_appeal_reviewer_sod CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR appeal_reviewer_employee_id IS NULL
        OR ((owner_employee_id IS NULL OR appeal_reviewer_employee_id <> owner_employee_id)
            AND (decision_employee_id IS NULL OR appeal_reviewer_employee_id <> decision_employee_id)))
      NOT VALID
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_customer_link_scope'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_customer_link_scope CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR (customer_id IS NULL AND customer_name IS NULL)
        OR (upper(coalesce(source_type,''))='CUSTOMER'
            AND customer_id IS NOT NULL AND customer_name IS NOT NULL))
      NOT VALID
    $ddl$;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.conname='ck_p014_appeal_result'
      AND n.nspname='reward' AND t.relname='discipline_case'
  ) THEN
    EXECUTE $ddl$
      ALTER TABLE reward.discipline_case
      ADD CONSTRAINT ck_p014_appeal_result CHECK (
        employee_event_type <> 'P014_DISCIPLINE'
        OR appeal_result IS NULL
        OR appeal_result IN ('UPHOLD','MODIFY','OVERTURN'))
      NOT VALID
    $ddl$;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_p014_business_no
  ON reward.discipline_case(tenant_id,business_no) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS uq_p014_source_fact
  ON reward.discipline_case(tenant_id,source_fact_key)
  WHERE employee_event_type='P014_DISCIPLINE' AND source_fact_key IS NOT NULL AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p014_subject
  ON reward.discipline_case(tenant_id,owner_employee_id,created_at DESC)
  WHERE employee_event_type='P014_DISCIPLINE' AND NOT is_deleted;
CREATE INDEX IF NOT EXISTS ix_p014_appeal_reviewer
  ON reward.discipline_case(tenant_id,appeal_reviewer_employee_id,updated_at DESC)
  WHERE employee_event_type='P014_DISCIPLINE' AND appeal_reviewer_employee_id IS NOT NULL AND NOT is_deleted;

ALTER TABLE reward.discipline_case ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_discipline_case ON reward.discipline_case;
CREATE POLICY p_tenant_discipline_case ON reward.discipline_case
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(
  id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P014','P014-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
  SELECT 1 FROM core.sequence_rule
  WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P014' AND NOT is_deleted);

INSERT INTO iam.permission(
  id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,
  created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,v.risk,now(),now(),false
FROM (VALUES
  ('p014.discipline.create','P014纪律案件登记','CREATE','HIGH'),
  ('p014.discipline.read','P014纪律案件读取','READ','HIGH'),
  ('p014.discipline.investigate','P014止险与调查','INVESTIGATE','HIGH'),
  ('p014.discipline.decide','P014责任评审与决定','DECIDE','CRITICAL'),
  ('p014.discipline.appeal','P014申辩送达与独立申诉','APPEAL','CRITICAL'),
  ('p014.discipline.remediate','P014影响关闭观察整改归档','REMEDIATE','CRITICAL'),
  ('p014.discipline.monitor','P014纪律流程监控','MONITOR','NORMAL')
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
    AND process_code='P014' AND enabled AND NOT is_deleted
  ORDER BY created_at,id LIMIT 1;

  IF target_definition_id IS NULL THEN
    target_definition_id:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(
      id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      target_definition_id,'${sjg_tenant_id}'::uuid,'P014','纪律责任与申诉',
      '绩效成长福利','reward','discipline_case',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_version
      WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id
        AND status='PUBLISHED' AND checksum='phase11-p014-c0-v2' AND NOT is_deleted) THEN
    target_version_id:=gen_random_uuid();
    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,definition_json,checksum,
      created_at,updated_at,is_deleted)
    SELECT target_version_id,'${sjg_tenant_id}'::uuid,target_definition_id,
      coalesce(max(version_no),0)+1,'DRAFT',
      '{"processCode":"P014","source":"PHASE11_WORKFLOW_CONTRACT","nodes":["S01","S02","S03","S04","S05","S06","S07","S08","S09","S10","S11","S12","END"]}'::jsonb,
      'phase11-p014-c0-v2',now(),now(),false
    FROM workflow.wf_version
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=target_definition_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,
      created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','线索登记','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','先行止险','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"investigatorCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','调查','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"investigatorCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','员工申辩','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','责任评审','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','决定审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"specialistCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','送达确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"employeeCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','影响执行','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"remediationCandidateIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','独立申诉复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"appealReviewerIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','核心案件关闭','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"remediationCandidateIds"}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S11','观察整改','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"remediationCandidateIds"}'::jsonb,110,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S12','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"remediationCandidateIds"}'::jsonb,120,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'END','已归档','END',NULL,130,now(),now(),false);

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,
      condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S01','REGISTER_LEAD','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S02','APPLY_SAFETY_MEASURE','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S03','COMPLETE_INVESTIGATION','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S04','SUBMIT_DEFENSE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S05','COMPLETE_RESPONSIBILITY_REVIEW','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S06','APPROVE_DECISION','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S07','ACKNOWLEDGE_SERVICE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S08','EXECUTE_IMPACTS','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S09','RESOLVE_APPEAL','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S10','CLOSE_CORE_CASE','S11',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S11','COMPLETE_OBSERVATION','S12',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,target_version_id,'S12','ARCHIVE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version
    SET status='PUBLISHED',effective_at=now(),updated_at=now()
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=target_version_id AND status='DRAFT';
  END IF;

  IF NOT EXISTS (
      SELECT 1 FROM workflow.wf_form_definition
      WHERE tenant_id='${sjg_tenant_id}'::uuid
        AND form_code='CTR-P014-F01' AND process_code='P014'
        AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(
      id,tenant_id,form_code,form_name,process_code,node_code,version_no,
      field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
      enabled,created_at,updated_at,is_deleted)
    VALUES(
      gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P014-F01',
      '纪律责任与申诉-线索登记单','P014','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"business_no":{"type":"string","readOnly":true},"subject":{"type":"string"},"reason":{"type":"string"},"owner_employee_id":{"type":"string"},"owner_center_id":{"type":"string"},"fact_summary":{"type":"string"},"source_fact_key":{"type":"string"},"source_type":{"type":"string"},"impact_level":{"type":"string"}},"required":["subject","reason","owner_employee_id","owner_center_id","fact_summary","source_fact_key","source_type","impact_level"]}'::jsonb,
      '{"sections":["纪律线索","被调查员工","来源与影响"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","current_node_code","version_no","investigator_employee_id","decision_employee_id","appeal_reviewer_employee_id","closed_at","archived_at"]}'::jsonb,
      '{"employee":"SELF_CASE","center":"CENTER_CASE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":[],"center":["subject","reason","owner_employee_id","owner_center_id","fact_summary","source_fact_key","source_type","impact_level","customer_id","customer_name"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
