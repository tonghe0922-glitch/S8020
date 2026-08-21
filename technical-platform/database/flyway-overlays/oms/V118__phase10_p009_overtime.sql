-- PHASE-10 / P009: overtime, actual labor facts, HR review, compensation and payroll receipt on canonical attendance.overtime_request.
SET ROLE sjg_owner;

ALTER TABLE attendance.overtime_request
    ADD COLUMN IF NOT EXISTS emergency_fact BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS necessity_checked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supervisor_decision VARCHAR(16),
    ADD COLUMN IF NOT EXISTS supervisor_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supervisor_rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS actual_duration_hours NUMERIC(18,6),
    ADD COLUMN IF NOT EXISTS actual_fact_recorded_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS result_accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS hr_reviewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS compensation_plan VARCHAR(16),
    ADD COLUMN IF NOT EXISTS compensation_planned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS payroll_reference VARCHAR(64),
    ADD COLUMN IF NOT EXISTS payroll_receipt_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p009_supervisor_decision') THEN
    ALTER TABLE attendance.overtime_request ADD CONSTRAINT ck_p009_supervisor_decision CHECK (supervisor_decision IS NULL OR supervisor_decision IN ('APPROVED','REJECTED'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p009_compensation_plan') THEN
    ALTER TABLE attendance.overtime_request ADD CONSTRAINT ck_p009_compensation_plan CHECK (compensation_plan IS NULL OR compensation_plan IN ('WAGE','TIME_OFF','MIXED'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p009_actual_duration_nonnegative') THEN
    ALTER TABLE attendance.overtime_request ADD CONSTRAINT ck_p009_actual_duration_nonnegative CHECK (actual_duration_hours IS NULL OR actual_duration_hours >= 0);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_p009_overtime_employee_window ON attendance.overtime_request(tenant_id,owner_employee_id,start_at,end_at) WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS ux_p009_timeoff_ledger_seq ON attendance.overtime_request_item(tenant_id,master_id,item_seq) WHERE field_code='TIME_OFF_LEDGER' AND NOT is_deleted;

CREATE OR REPLACE FUNCTION attendance.prevent_p009_timeoff_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.field_code='TIME_OFF_LEDGER' THEN RAISE EXCEPTION 'P009 time-off ledger is append-only'; END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_p009_timeoff_ledger_immutable ON attendance.overtime_request_item;
CREATE TRIGGER trg_p009_timeoff_ledger_immutable BEFORE UPDATE OR DELETE ON attendance.overtime_request_item FOR EACH ROW EXECUTE FUNCTION attendance.prevent_p009_timeoff_ledger_mutation();

INSERT INTO core.sequence_rule(id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P009','P009-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P009' AND NOT is_deleted);

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
  ('p009.overtime.submit','P009加班调休申请','SUBMIT'),
  ('p009.overtime.read','P009加班调休读取','READ'),
  ('p009.overtime.review','P009主管审批','REVIEW'),
  ('p009.overtime.hr','P009人事复核与薪酬方案','HR'),
  ('p009.overtime.manage','P009加班任务与成果管理','MANAGE'),
  ('p009.overtime.monitor','P009运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE d uuid; v uuid;
BEGIN
  SELECT id INTO d FROM workflow.wf_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P009' AND enabled AND NOT is_deleted ORDER BY created_at,id LIMIT 1;
  IF d IS NULL THEN
    d:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(d,'${sjg_tenant_id}'::uuid,'P009','加班与调休','全员公共能力','attendance','overtime_request',true,now(),now(),false);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=d AND status='PUBLISHED' AND NOT is_deleted) THEN
    v:=gen_random_uuid();
    INSERT INTO workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
    VALUES(v,'${sjg_tenant_id}'::uuid,d,1,'DRAFT',jsonb_build_object('processCode','P009','source','PHASE10_SOURCE_CONTRACT','states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','END')),'phase10-p009-v1',now(),now(),false);
    INSERT INTO workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','事前申请/紧急事实登记','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','必要性与任务校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','主管审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','实际考勤与劳动事实','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','成果验收','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','人事复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"hrCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','法定工资/调休方案','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"hrCandidateIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','薪酬回执','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"hrCandidateIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"hrCandidateIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'END','已关闭','END',NULL,100,now(),now(),false);
    INSERT INTO workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','SUBMIT_OVERTIME','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','VALIDATE_NECESSITY','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','APPROVE_OVERTIME','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','REJECT_OVERTIME','END',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','RECORD_ACTUAL_FACT','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','ACCEPT_RESULT','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','HR_REVIEW','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','SET_COMPENSATION_PLAN','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','ACK_PAYROLL_RECEIPT','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','ARCHIVE','END',NULL,false,now(),now(),false);
    UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now() WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=v AND status='DRAFT';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND form_code='CTR-P009-F01' AND process_code='P009' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,enabled,created_at,updated_at,is_deleted)
    VALUES(gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P009-F01','加班与调休-申请/紧急事实','P009','S01',1,
      '{"type":"object","properties":{"subject":{"type":"string"},"attendance_type":{"type":"string"},"start_at":{"type":"string","format":"date-time"},"end_at":{"type":"string","format":"date-time"},"emergency_fact":{"type":"boolean"}},"required":["subject","attendance_type","start_at","end_at"]}'::jsonb,
      '{"sections":["申请或紧急事实","计划时间"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","version_no","supervisor_decision","actual_fact_recorded_at","hr_reviewed_at","compensation_plan","payroll_receipt_at"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","attendance_type","start_at","end_at","emergency_fact"],"center":[],"tech":[]}'::jsonb,true,now(),now(),false);
  END IF;
END $$;
RESET ROLE;
