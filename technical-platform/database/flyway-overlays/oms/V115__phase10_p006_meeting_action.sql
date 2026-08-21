-- PHASE-10 / P006: canonical meeting + action-item closure. Additive only; V10 remains immutable.
SET ROLE sjg_owner;

ALTER TABLE collaboration.meeting
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS minutes_text TEXT,
    ADD COLUMN IF NOT EXISTS minutes_confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

ALTER TABLE collaboration.meeting_item
    ADD COLUMN IF NOT EXISTS action_owner_employee_id UUID,
    ADD COLUMN IF NOT EXISTS action_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS action_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS execution_evidence TEXT,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS accepted_by UUID,
    ADD COLUMN IF NOT EXISTS rework_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version_no INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_meeting_item_p006_action_status') THEN
    ALTER TABLE collaboration.meeting_item ADD CONSTRAINT ck_meeting_item_p006_action_status
      CHECK (action_status IS NULL OR action_status IN ('PENDING','ATTENDED','LEAVE','OPEN','EXECUTED','REWORK','ACCEPTED','OVERDUE'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_meeting_item_p006_rework_count') THEN
    ALTER TABLE collaboration.meeting_item ADD CONSTRAINT ck_meeting_item_p006_rework_count CHECK (rework_count >= 0);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_meeting_item_p006_owner_due
  ON collaboration.meeting_item(tenant_id, action_owner_employee_id, action_due_at)
  WHERE field_code='ACTION_ITEM' AND NOT is_deleted;

INSERT INTO core.sequence_rule(id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P006','P006-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P006' AND NOT is_deleted);

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
  ('p006.meeting.create','P006会议创建','CREATE'),
  ('p006.meeting.read','P006会议读取','READ'),
  ('p006.meeting.manage','P006会议管理','MANAGE'),
  ('p006.meeting.action','P006会议参与及行动执行','ACTION'),
  ('p006.meeting.accept','P006行动项验收','ACCEPT'),
  ('p006.meeting.monitor','P006会议运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE p_definition_id uuid; p_version_id uuid;
BEGIN
  SELECT id INTO p_definition_id FROM workflow.wf_definition
   WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P006' AND enabled AND NOT is_deleted
   ORDER BY created_at,id LIMIT 1;
  IF p_definition_id IS NULL THEN
    p_definition_id := gen_random_uuid();
    INSERT INTO workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
    VALUES(p_definition_id,'${sjg_tenant_id}'::uuid,'P006','会议与行动项','全员公共能力','collaboration','meeting',true,now(),now(),false);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=p_definition_id AND status='PUBLISHED' AND NOT is_deleted) THEN
    p_version_id := gen_random_uuid();
    INSERT INTO workflow.wf_version(id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_at,updated_at,is_deleted)
    VALUES(p_version_id,'${sjg_tenant_id}'::uuid,p_definition_id,1,'DRAFT',NULL,
      jsonb_build_object('processCode','P006','source','PHASE10_SOURCE_CONTRACT','states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','S11','END')),
      'phase10-p006-source-flow-v1',now(),now(),false);

    INSERT INTO workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S01','议题征集','START',NULL,10,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','材料完整性检查','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,20,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','会议发布','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,30,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','签到与请假','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"participantEmployeeIds"}'::jsonb,40,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','会议召开','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,50,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','主持人确认纪要','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,60,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','行动项生成','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,70,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','责任人执行','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"participantEmployeeIds"}'::jsonb,80,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','验收与返工','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,90,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','逾期升级','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,100,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S11','归档复盘','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,110,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'END','已关闭','END',NULL,120,now(),now(),false);

    INSERT INTO workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S01','SUBMIT_AGENDA','S02',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','CONFIRM_MATERIALS','S03',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','PUBLISH_MEETING','S04',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','COMPLETE_ATTENDANCE','S05',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','COMPLETE_MEETING','S06',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','CONFIRM_MINUTES','S07',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','GENERATE_ACTION_ITEMS','S08',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','SUBMIT_ACTION_EVIDENCE','S09',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','ACCEPT_ACTIONS','S10',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','RETURN_ACTIONS','S08',NULL,true,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','RESOLVE_OVERDUE','S11',NULL,false,now(),now(),false),
      (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S11','ARCHIVE','END',NULL,false,now(),now(),false);

    UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now()
     WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND NOT is_deleted;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND form_code='CTR-P006-F01' AND process_code='P006' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
    INSERT INTO workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,enabled,created_at,updated_at,is_deleted)
    VALUES(gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P006-F01','会议与行动项-议题征集','P006','S01',1,
      '{"type":"object","properties":{"process_code":{"type":"string","readOnly":true},"official_subject":{"type":"string"},"official_content":{"type":"string"},"start_at":{"type":"string","format":"date-time"},"venue_channel":{"type":"string"},"visibility_level":{"type":"string"},"participant_employee_ids":{"type":"array"}},"required":["official_subject","official_content","start_at","participant_employee_ids"]}'::jsonb,
      '{"sections":["会议信息","议题材料","参会范围"]}'::jsonb,
      '{"serverAuthoritative":["process_code","business_no","workflow_instance_id","status","version_no"]}'::jsonb,
      '{"employee":"PARTICIPANT","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
      '{"employee":[],"center":["official_subject","official_content","start_at","venue_channel","visibility_level","participant_employee_ids"],"tech":[]}'::jsonb,
      true,now(),now(),false);
  END IF;
END $$;

RESET ROLE;
