-- PHASE-10 / P008: leave, quota ledger, handover and attendance closure on canonical attendance.leave_request.
SET ROLE sjg_owner;

ALTER TABLE attendance.leave_request
    ADD COLUMN IF NOT EXISTS quota_reserved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS handover_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decision VARCHAR(16),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quota_settled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attendance_marked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS leave_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS returned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quota_adjusted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS day_closed_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p008_leave_decision') THEN
    ALTER TABLE attendance.leave_request ADD CONSTRAINT ck_p008_leave_decision
      CHECK (decision IS NULL OR decision IN ('APPROVED','REJECTED'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_p008_leave_employee_window
  ON attendance.leave_request(tenant_id,owner_employee_id,start_at,end_at)
  WHERE NOT is_deleted;
CREATE UNIQUE INDEX IF NOT EXISTS ux_p008_quota_ledger_seq
  ON attendance.leave_request_item(tenant_id,master_id,item_seq)
  WHERE field_code='QUOTA_LEDGER' AND NOT is_deleted;

CREATE OR REPLACE FUNCTION attendance.prevent_p008_quota_ledger_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.field_code='QUOTA_LEDGER' THEN
    RAISE EXCEPTION 'P008 quota ledger is append-only';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_p008_quota_ledger_immutable ON attendance.leave_request_item;
CREATE TRIGGER trg_p008_quota_ledger_immutable
BEFORE UPDATE OR DELETE ON attendance.leave_request_item
FOR EACH ROW EXECUTE FUNCTION attendance.prevent_p008_quota_ledger_mutation();

INSERT INTO core.sequence_rule(id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P008','P008-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P008' AND NOT is_deleted);

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
  ('p008.leave.submit','P008请假申请','SUBMIT'),
  ('p008.leave.read','P008请假读取','READ'),
  ('p008.leave.review','P008请假审批','REVIEW'),
  ('p008.leave.manage','P008假期额度与考勤管理','MANAGE'),
  ('p008.leave.monitor','P008请假运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE d uuid; v uuid;
BEGIN
  SELECT id INTO d FROM workflow.wf_definition
   WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P008' AND enabled AND NOT is_deleted
   ORDER BY created_at,id LIMIT 1;
  IF d IS NULL THEN
    d:=gen_random_uuid();
    INSERT INTO workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(d,'${sjg_tenant_id}'::uuid,'P008','请假与考勤','全员公共能力','attendance','leave_request',true,now(),now(),false);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=d AND status='PUBLISHED' AND NOT is_deleted) THEN
    v:=gen_random_uuid();
    INSERT INTO workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
    VALUES(v,'${sjg_tenant_id}'::uuid,d,1,'DRAFT',jsonb_build_object('processCode','P008','source','PHASE10_SOURCE_CONTRACT','states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','END')),'phase10-p008-v1',now(),now(),false);
    INSERT INTO workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','请假申请','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','假期额度预占','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','工作交接与代理','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','预占转扣减/驳回释放','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','排班与考勤标记','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','实际休假','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','销假/提前返岗/变更','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','差额账本调整','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S10','考勤日结与归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds"}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'END','已关闭','END',NULL,110,now(),now(),false);
    INSERT INTO workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','SUBMIT_LEAVE','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','RESERVE_QUOTA','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','CONFIRM_HANDOVER','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','APPROVE_LEAVE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','REJECT_LEAVE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','COMMIT_QUOTA','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','RELEASE_QUOTA','END',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','MARK_ATTENDANCE','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','START_LEAVE','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','RETURN_TO_WORK','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','CHANGE_LEAVE','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','ADJUST_QUOTA','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S10','CLOSE_DAY','END',NULL,false,now(),now(),false);
    UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now()
      WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=v AND status='DRAFT';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND form_code='CTR-P008-F01' AND process_code='P008' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,enabled,created_at,updated_at,is_deleted)
    VALUES(gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P008-F01','请假与考勤-请假申请','P008','S01',1,
      '{"type":"object","properties":{"subject":{"type":"string"},"attendance_type":{"type":"string"},"start_at":{"type":"string","format":"date-time"},"end_at":{"type":"string","format":"date-time"},"quota_account_id":{"type":"string"},"quota_amount":{"type":"number"},"handover_agent_id":{"type":"string"},"known_impact":{"type":"string"}},"required":["subject","attendance_type","start_at","end_at","quota_account_id","quota_amount"]}'::jsonb,
      '{"sections":["申请","额度","交接"]}'::jsonb,
      '{"serverAuthoritative":["business_no","workflow_instance_id","status","version_no","decision","quota_reserved_at","quota_settled_at"]}'::jsonb,
      '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":["subject","attendance_type","start_at","end_at","quota_account_id","quota_amount","handover_agent_id","known_impact"],"center":[],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;
RESET ROLE;
