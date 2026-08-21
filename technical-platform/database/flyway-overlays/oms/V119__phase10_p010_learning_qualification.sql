-- PHASE-10 / P010: learning, examination, certification and qualification closure.
SET ROLE sjg_owner;

ALTER TABLE learning.learning_assignment
    ADD COLUMN IF NOT EXISTS phase_node_code VARCHAR(16) NOT NULL DEFAULT 'S01',
    ADD COLUMN IF NOT EXISTS content_published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS risk_assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS learning_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS exam_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS practical_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS certified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS certified_by UUID,
    ADD COLUMN IF NOT EXISTS qualification_activated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS permission_linked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retraining_checked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p010_completion_rate') THEN
    ALTER TABLE learning.learning_assignment ADD CONSTRAINT ck_p010_completion_rate
      CHECK (completion_rate IS NULL OR (completion_rate >= 0 AND completion_rate <= 100));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p010_score_1000') THEN
    ALTER TABLE learning.learning_assignment ADD CONSTRAINT ck_p010_score_1000
      CHECK (score_1000 IS NULL OR (score_1000 >= 0 AND score_1000 <= 1000));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p010_practical_result') THEN
    ALTER TABLE learning.learning_assignment ADD CONSTRAINT ck_p010_practical_result
      CHECK (practical_result IS NULL OR practical_result IN ('通过','补训','不通过','不适用'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_p010_qualification_dates') THEN
    ALTER TABLE learning.learning_assignment ADD CONSTRAINT ck_p010_qualification_dates
      CHECK (qualification_expire_date IS NULL OR qualification_effective_date IS NULL OR qualification_expire_date >= qualification_effective_date);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS learning.learning_assignment_evidence (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    evidence_type VARCHAR(48) NOT NULL,
    actor_employee_id UUID,
    score_1000 BIGINT,
    completion_rate NUMERIC(9,6),
    practical_result VARCHAR(16),
    evidence_text TEXT,
    evidence_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_learning_assignment_evidence_assignment
  ON learning.learning_assignment_evidence(tenant_id,assignment_id,created_at,id);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_learning_assignment_evidence_score') THEN
    ALTER TABLE learning.learning_assignment_evidence ADD CONSTRAINT ck_learning_assignment_evidence_score CHECK (score_1000 IS NULL OR score_1000 BETWEEN 0 AND 1000);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_learning_assignment_evidence_progress') THEN
    ALTER TABLE learning.learning_assignment_evidence ADD CONSTRAINT ck_learning_assignment_evidence_progress CHECK (completion_rate IS NULL OR completion_rate BETWEEN 0 AND 100);
  END IF;
END $$;

CREATE OR REPLACE FUNCTION learning.prevent_assignment_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'learning assignment evidence is append-only'; END $$;
DROP TRIGGER IF EXISTS trg_assignment_evidence_immutable ON learning.learning_assignment_evidence;
CREATE TRIGGER trg_assignment_evidence_immutable BEFORE UPDATE OR DELETE ON learning.learning_assignment_evidence
FOR EACH ROW EXECUTE FUNCTION learning.prevent_assignment_evidence_mutation();

CREATE TABLE IF NOT EXISTS learning.qualification_permission_binding (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID NOT NULL,
    course_version_id VARCHAR(64) NOT NULL,
    position_id UUID,
    role_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_deleted BOOLEAN NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_qualification_permission_binding
  ON learning.qualification_permission_binding(tenant_id,course_version_id,coalesce(position_id,'00000000-0000-0000-0000-000000000000'::uuid),role_id)
  WHERE NOT is_deleted;

ALTER TABLE learning.learning_assignment_evidence ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_learning_assignment_evidence ON learning.learning_assignment_evidence;
CREATE POLICY p_tenant_learning_assignment_evidence ON learning.learning_assignment_evidence
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);
ALTER TABLE learning.qualification_permission_binding ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_learning_qualification_permission_binding ON learning.qualification_permission_binding;
CREATE POLICY p_tenant_learning_qualification_permission_binding ON learning.qualification_permission_binding
  USING (tenant_id=current_setting('app.tenant_id',true)::uuid)
  WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);

INSERT INTO core.sequence_rule(id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P010','P010-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (SELECT 1 FROM core.sequence_rule WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P010' AND NOT is_deleted);

INSERT INTO iam.permission(id,tenant_id,permission_code,permission_name,resource_type,action_code,risk_level,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,v.code,v.name,'PROCESS',v.action,'NORMAL',now(),now(),false
FROM (VALUES
 ('p010.learning.read','P010学习资格读取','READ'),
 ('p010.learning.manage','P010学习资格管理','MANAGE'),
 ('p010.learning.complete','P010学习实操完成','COMPLETE'),
 ('p010.learning.exam','P010考试提交','EXAM'),
 ('p010.learning.certify','P010专业认证','CERTIFY'),
 ('p010.learning.monitor','P010运行监控','MONITOR')
) AS v(code,name,action)
WHERE NOT EXISTS (SELECT 1 FROM iam.permission p WHERE p.tenant_id='${sjg_tenant_id}'::uuid AND p.permission_code=v.code AND NOT p.is_deleted);

DO $$
DECLARE d UUID; v UUID;
BEGIN
 SELECT id INTO d FROM workflow.wf_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND process_code='P010' AND enabled AND NOT is_deleted ORDER BY created_at,id LIMIT 1;
 IF d IS NULL THEN
  d:=gen_random_uuid();
  INSERT INTO workflow.wf_definition(id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
  VALUES(d,'${sjg_tenant_id}'::uuid,'P010','员工学习、考试与资格','全员公共能力','learning','learning_assignment',true,now(),now(),false);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM workflow.wf_version WHERE tenant_id='${sjg_tenant_id}'::uuid AND definition_id=d AND status='PUBLISHED' AND NOT is_deleted) THEN
  v:=gen_random_uuid();
  INSERT INTO workflow.wf_version(id,tenant_id,definition_id,version_no,status,definition_json,checksum,created_at,updated_at,is_deleted)
  VALUES(v,'${sjg_tenant_id}'::uuid,d,1,'DRAFT',jsonb_build_object('processCode','P010','source','PHASE10_SOURCE_CONTRACT','states',jsonb_build_array('S01','S02','S03','S04','S05','S06','S07','S08','S09','S10','END')),'phase10-p010-v1',now(),now(),false);
  INSERT INTO workflow.wf_node(id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted) VALUES
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','课程/制度版本发布','START',NULL,10,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','按岗位风险指派','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,20,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','员工学习','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,30,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','1000分制考试','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,40,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','线下实操','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"targetEmployeeIds"}'::jsonb,50,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','主管/专业人员认证','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"certifierCandidateIds"}'::jsonb,60,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','资格生效','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,70,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','岗位权限联动','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,80,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','到期复训/复证','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,90,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S10','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"managerCandidateIds","allowInitiator":true}'::jsonb,100,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'END','已关闭','END',NULL,110,now(),now(),false);
  INSERT INTO workflow.wf_transition(id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted) VALUES
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S01','PUBLISH_CONTENT','S02',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S02','ASSIGN_BY_RISK','S03',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S03','COMPLETE_LEARNING','S04',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S04','RECORD_EXAM','S05',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S05','RECORD_PRACTICAL','S06',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','CERTIFY','S07',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S06','RETURN_FOR_TRAINING','S03',NULL,true,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S07','ACTIVATE_QUALIFICATION','S08',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S08','LINK_PERMISSIONS','S09',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S09','COMPLETE_RETRAINING_CHECK','S10',NULL,false,now(),now(),false),
   (gen_random_uuid(),'${sjg_tenant_id}'::uuid,v,'S10','ARCHIVE','END',NULL,false,now(),now(),false);
  UPDATE workflow.wf_version SET status='PUBLISHED',effective_at=now(),updated_at=now() WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=v AND status='DRAFT';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM workflow.wf_form_definition WHERE tenant_id='${sjg_tenant_id}'::uuid AND form_code='CTR-P010-F03' AND process_code='P010' AND node_code='S01' AND enabled AND NOT is_deleted) THEN
  INSERT INTO workflow.wf_form_definition(id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,enabled,created_at,updated_at,is_deleted)
  VALUES(gen_random_uuid(),'${sjg_tenant_id}'::uuid,'CTR-P010-F03','员工学习、考试与资格-课程·制度版本发布-任务分派与资源计划单','P010','S01',1,
   '{"type":"object","properties":{"course_version_id":{"type":"string"},"content_version":{"type":"string"},"period_or_course_no":{"type":"string"},"owner_employee_id":{"type":"string"}},"required":["course_version_id","content_version","period_or_course_no","owner_employee_id"]}'::jsonb,
   '{"sections":["课程版本","任务与人员"]}'::jsonb,
   '{"serverAuthoritative":["business_no","workflow_instance_id","status","version_no","score_1000","qualification_effective_date","qualification_expire_date"]}'::jsonb,
   '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
   '{"employee":[],"center":["course_version_id","content_version","period_or_course_no","owner_employee_id"],"tech":[]}'::jsonb,true,now(),now(),false);
 END IF;
END $$;
RESET ROLE;
