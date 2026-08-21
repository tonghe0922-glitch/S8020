-- PHASE-09 / P004: source-backed generic request + approval workflow.
-- Source flow: S01 choose business item -> S02 application/attachments -> S03 pre-check ->
-- S04 submit approval -> S05 dynamic approval/countersign -> S06 create execution task ->
-- S07 executor result -> S08 independent acceptance -> S09 exception/compensation -> S10 archive -> END.
-- S01 is a portal selection state before a persistent request exists. POST creates at S02,
-- freezes EMP-P004-F01 against the started workflow instance, then SUBMIT moves to S03.
SET ROLE sjg_owner;

INSERT INTO core.sequence_rule(
    id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P004','P004-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
    SELECT 1 FROM core.sequence_rule
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P004' AND not is_deleted
);

DO $$
DECLARE
    p_definition_id uuid;
    p_version_id uuid;
BEGIN
    SELECT d.id INTO p_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid AND d.process_code='P004' AND d.enabled AND not d.is_deleted
     ORDER BY d.created_at,d.id LIMIT 1;

    IF p_definition_id IS NULL THEN
        p_definition_id := gen_random_uuid();
        INSERT INTO workflow.wf_definition(
            id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
        VALUES (p_definition_id,'${sjg_tenant_id}'::uuid,'P004','通用申请与审批','全员公共能力',
                'workflow','generic_request',true,now(),now(),false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_version v
         WHERE v.tenant_id='${sjg_tenant_id}'::uuid AND v.definition_id=p_definition_id
           AND v.status='PUBLISHED' AND not v.is_deleted
    ) THEN
        p_version_id := gen_random_uuid();
        INSERT INTO workflow.wf_version(
            id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_at,updated_at,is_deleted)
        VALUES (
            p_version_id,'${sjg_tenant_id}'::uuid,p_definition_id,1,'DRAFT',NULL,
            jsonb_build_object(
                'processCode','P004','source','PHASE09_SOURCE_CONTRACT',
                'states',jsonb_build_array('S02','S03','S04','S05','S06','S07','S08','S09','S10','END'),
                'guards',jsonb_build_array('anti-self-approval','distinct-S04-S05','independent-S07-S08')),
            'phase09-p004-source-flow-v1',now(),now(),false
        );

        INSERT INTO workflow.wf_node(
            id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','填写申请与附件','START',NULL,20,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','前置规则校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,30,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','提交审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,40,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','动态审批与会签','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,50,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','批准后生成执行任务','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,60,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','执行人提交结果','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,70,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','独立验收','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,80,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','异常补偿','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,90,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','归档','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"actionCandidateIds"}'::jsonb,100,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'END','已关闭','END',NULL,110,now(),now(),false);

        INSERT INTO workflow.wf_transition(
            id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','SUBMIT','S03',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','WITHDRAW','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','ACCEPT','S04',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','RETURN','S02',NULL,true,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','SUBMIT_APPROVAL','S05',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','RETURN','S02',NULL,true,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','APPROVE','S06',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','RETURN','S02',NULL,true,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','CREATE_TASK','S07',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','SUBMIT_RESULT','S08',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','ACCEPT_RESULT','S09',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','RETURN','S07',NULL,true,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','COMPLETE','S10',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S09','RETRY','S07',NULL,true,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S10','ARCHIVE','END',NULL,false,now(),now(),false);

        UPDATE workflow.wf_version
           SET status='PUBLISHED',effective_at=now(),updated_at=now()
         WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND not is_deleted;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'P004 workflow version could not be published' USING ERRCODE = '55000';
        END IF;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_form_definition f
         WHERE f.tenant_id='${sjg_tenant_id}'::uuid AND f.form_code='EMP-P004-F01'
           AND f.process_code='P004' AND f.node_code='S02' AND f.enabled AND not f.is_deleted
    ) THEN
        INSERT INTO workflow.wf_form_definition(
            id,tenant_id,form_code,form_name,process_code,node_code,version_no,
            field_schema,layout_schema,validation_schema,visibility_matrix,edit_matrix,
            enabled,created_at,updated_at,is_deleted)
        VALUES (
            gen_random_uuid(),'${sjg_tenant_id}'::uuid,'EMP-P004-F01',
            '通用申请与审批-填写申请与附件-申请/登记单','P004','S02',1,
            '{"type":"object","properties":{"process_instance_no":{"type":"string","readOnly":true},"submission_no":{"type":"string","readOnly":true},"process_code":{"type":"string","readOnly":true},"form_code":{"type":"string","readOnly":true},"form_version":{"type":"integer","readOnly":true},"request_type":{"type":"string"},"subject":{"type":"string"},"reason":{"type":"string"},"requested_result":{"type":"string"},"business_date":{"type":"string","format":"date"},"priority":{"type":"string"},"risk_level":{"type":"string"},"amount":{"type":"number"}},"required":["request_type","subject","business_date"]}'::jsonb,
            '{"sections":["系统标识","申请信息"]}'::jsonb,
            '{"serverAuthoritative":["process_instance_no","submission_no","process_code","form_code","form_version"]}'::jsonb,
            '{"employee":"SELF","center":"AUTHORIZED_SCOPE","tech":"METADATA_ONLY"}'::jsonb,
            '{"employee":["request_type","subject","reason","requested_result","business_date","priority","risk_level","amount"],"center":[],"tech":[]}'::jsonb,
            true,now(),now(),false
        );
    END IF;
END
$$;

RESET ROLE;
