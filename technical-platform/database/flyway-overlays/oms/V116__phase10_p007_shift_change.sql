-- PHASE-10 / P007: scheduling and shift-change closure on canonical attendance.shift_change_request.
SET ROLE sjg_owner;

ALTER TABLE attendance.shift_change_request
    ADD COLUMN IF NOT EXISTS template_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_employee_id UUID,
    ADD COLUMN IF NOT EXISTS replacement_employee_id UUID,
    ADD COLUMN IF NOT EXISTS qualification_checked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS continuous_work_hours NUMERIC(18,6),
    ADD COLUMN IF NOT EXISTS conflict_checked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS employee_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attendance_linked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS catering_linked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS shuttle_linked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS day_closed_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p007_continuous_work_nonnegative') THEN
    ALTER TABLE attendance.shift_change_request ADD CONSTRAINT ck_p007_continuous_work_nonnegative
      CHECK (continuous_work_hours IS NULL OR continuous_work_hours >= 0);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_p007_employee_window
  ON attendance.shift_change_request(tenant_id,target_employee_id,start_at,end_at)
  WHERE NOT is_deleted;

INSERT INTO core.sequence_rule(id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P007','P007-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P007' AND NOT is_deleted);

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
  ('p007.schedule.create','P007排班创建','CREATE'),
  ('p007.schedule.read','P007排班读取','READ'),
  ('p007.schedule.manage','P007排班管理','MANAGE'),
  ('p007.shift.submit','P007换班申请','SUBMIT'),
  ('p007.shift.confirm','P007员工确认','CONFIRM'),
  ('p007.shift.review','P007换班审批','REVIEW'),
  ('p007.schedule.monitor','P007排班运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE d uuid; v uuid;
BEGIN
  SELECT id INTO d FROM workflow.wf_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P007' AND enabled AND NOT is_deleted ORDER BY created_at,id LIMIT 1;
  IF d IS NULL THEN
    d:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(d,'${sjg_tenant_id}'::uuid,'P007','排班与班次调整','全员公共能力','attendance','shift_change_request',true,now(),now(),false);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=d AND status='PUBLISHED' AND NOT is_deleted) THEN
    v:=gen_random_uuid();
    INSERT INTO workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
    VALUES(v,'${sjg_tenant_id}'::uuid,d,1,'DRAFT',jsonb_build_object('processCode','P007','source','PHASE10_SOURCE_CONTRACT','states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','END')),'phase10-p007-v1',now(),now(),false);
    INSERT INTO workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','业务量与活动需求输入','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','班次模板匹配','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','资格与连续工时校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','主管发布排班','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','员工确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','换班替班申请','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','变更审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','考勤与餐饮班车联动','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','日结','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'END','已关闭','END',NULL,100,now(),now(),false);
    INSERT INTO workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','SUBMIT_DEMAND','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','MATCH_TEMPLATE','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','VALIDATE_SHIFT','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','PUBLISH_SCHEDULE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','CONFIRM_SCHEDULE','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','SUBMIT_SHIFT_CHANGE','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','APPROVE_CHANGE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','RETURN_CHANGE','S06',NULL,true,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','LINK_DEPENDENCIES','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','CLOSE_DAY','END',NULL,false,now(),now(),false);
    UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now() WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=v AND status='DRAFT';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND form_code='CTR-P007-F01' AND process_code='P007' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,enabled,created_at,updated_at,is_deleted)
    VALUES(gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P007-F01','排班与班次调整-需求输入','P007','S01',1,
      '{"type":"object","properties":{"subject":{"type":"string"},"target_employee_id":{"type":"string"},"start_at":{"type":"string","format":"date-time"},"end_at":{"type":"string","format":"date-time"},"template_code":{"type":"string"},"change_action":{"type":"string"}},"required":["subject","target_employee_id","start_at","end_at","change_action"]}'::jsonb,
      '{"sections":["需求","员工","班次窗口"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","version_no","qualification_checked_at","conflict_checked_at"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["change_action"],"center":["subject","target_employee_id","start_at","end_at","template_code","change_action"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;
RESET ROLE;
