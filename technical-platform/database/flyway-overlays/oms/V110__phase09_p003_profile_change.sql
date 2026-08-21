-- PHASE-09 / P003: source-backed employee profile change workflow.
-- Source: S01 view self -> S02 choose self-service fields -> S03 submit new values/proof ->
-- S04 sensitivity validation -> S05 HR/finance/domain verification -> S06 authoritative master update ->
-- S07 projection synchronization -> S08 notification/audit -> END closed.
-- S01/S02 are read/selection UI states before a persistent request exists; POST creates at S03.
SET ROLE sjg_owner;

INSERT INTO core.sequence_rule(
    id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P003','P003-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
    SELECT 1 FROM core.sequence_rule
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P003' AND not is_deleted
);

DO $$
DECLARE
    p_definition_id uuid;
    p_version_id uuid;
BEGIN
    SELECT d.id INTO p_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid AND d.process_code='P003' AND d.enabled AND not d.is_deleted
     ORDER BY d.created_at,d.id LIMIT 1;

    IF p_definition_id IS NULL THEN
        p_definition_id := gen_random_uuid();
        INSERT INTO workflow.wf_definition(
            id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
        VALUES (p_definition_id,'${sjg_tenant_id}'::uuid,'P003','个人资料变更','全员公共能力','hr','employee_profile_change',true,now(),now(),false);
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
            jsonb_build_object('processCode','P003','source','PHASE09_SOURCE_CONTRACT','states',
                jsonb_build_array('S03','S04','S05','S06','S07','S08','END')),
            'phase09-p003-source-flow-v1',now(),now(),false
        );

        INSERT INTO workflow.wf_node(
            id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','提交新值与证明','START',NULL,30,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','字段敏感级别校验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,40,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','人事/财务/归口岗核验','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,50,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','权威主档更新','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"applyCandidateIds"}'::jsonb,60,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','关联模块投影同步','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"applyCandidateIds"}'::jsonb,70,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','通知与审计','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"applyCandidateIds"}'::jsonb,80,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'END','已关闭','END',NULL,90,now(),now(),false);

        INSERT INTO workflow.wf_transition(
            id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','SUBMIT','S04',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','APPROVE','S05',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','APPROVE','S06',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','APPLY','S07',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','SYNC','S08',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','CLOSE','END',NULL,false,now(),now(),false);

        UPDATE workflow.wf_version
           SET status='PUBLISHED',effective_at=now(),updated_at=now()
         WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND not is_deleted;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'P003 workflow version could not be published' USING ERRCODE = '55000';
        END IF;
    END IF;
END
$$;

RESET ROLE;
