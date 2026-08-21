-- PHASE-09 / P002: permission request execution linkage and source-backed workflow runtime.
-- Source states: S02 personal/project supplemental request -> S03 business owner confirm ->
-- S04 data owner review -> S05 high-risk approval -> S06 permission effective ->
-- S07 periodic review -> S08 expiry/transfer/offboarding revoke -> closed.
SET ROLE sjg_owner;

CREATE TABLE IF NOT EXISTS iam.permission_request_grant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    permission_request_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    target_identity_id uuid NOT NULL,
    requested_role_id uuid NOT NULL,
    user_role_id uuid,
    grant_status varchar(16) NOT NULL DEFAULT 'REQUESTED',
    effective_start_at timestamptz NOT NULL,
    effective_end_at timestamptz NOT NULL,
    executed_by uuid,
    executed_at timestamptz,
    revoked_by uuid,
    revoked_at timestamptz,
    revoke_reason text,
    version_no integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_permission_request_grant_request UNIQUE (tenant_id, permission_request_id),
    CONSTRAINT ck_permission_request_grant_status CHECK (grant_status IN ('REQUESTED','ACTIVE','REVOKED')),
    CONSTRAINT ck_permission_request_grant_dates CHECK (effective_end_at > effective_start_at),
    CONSTRAINT fk_permission_request_grant_request FOREIGN KEY (permission_request_id) REFERENCES iam.permission_request(id),
    CONSTRAINT fk_permission_request_grant_user FOREIGN KEY (target_user_id) REFERENCES iam.user_account(id),
    CONSTRAINT fk_permission_request_grant_identity FOREIGN KEY (target_identity_id) REFERENCES iam.user_identity(id),
    CONSTRAINT fk_permission_request_grant_role FOREIGN KEY (requested_role_id) REFERENCES iam.role(id),
    CONSTRAINT fk_permission_request_grant_user_role FOREIGN KEY (user_role_id) REFERENCES iam.user_role(id),
    CONSTRAINT fk_permission_request_grant_created_by FOREIGN KEY (created_by) REFERENCES org.employee(id),
    CONSTRAINT fk_permission_request_grant_updated_by FOREIGN KEY (updated_by) REFERENCES org.employee(id),
    CONSTRAINT fk_permission_request_grant_executed_by FOREIGN KEY (executed_by) REFERENCES org.employee(id),
    CONSTRAINT fk_permission_request_grant_revoked_by FOREIGN KEY (revoked_by) REFERENCES org.employee(id)
);
CREATE INDEX IF NOT EXISTS idx_permission_request_grant_expiry
    ON iam.permission_request_grant(tenant_id, grant_status, effective_end_at);
CREATE INDEX IF NOT EXISTS idx_permission_request_grant_target
    ON iam.permission_request_grant(tenant_id, target_user_id, target_identity_id, grant_status);
ALTER TABLE iam.permission_request_grant ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_baseline ON iam.permission_request_grant;
CREATE POLICY p_tenant_baseline ON iam.permission_request_grant
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.permission_request_grant TO sjg_api_runtime, sjg_worker_runtime;

INSERT INTO core.sequence_rule(
    id,tenant_id,rule_code,prefix_template,date_pattern,current_value,step,created_at,updated_at,is_deleted)
SELECT gen_random_uuid(),'${sjg_tenant_id}'::uuid,'P002','P002-','yyyyMMdd',0,1,now(),now(),false
WHERE NOT EXISTS (
    SELECT 1 FROM core.sequence_rule
    WHERE tenant_id='${sjg_tenant_id}'::uuid AND rule_code='P002' AND not is_deleted
);

DO $$
DECLARE
    p_definition_id uuid;
    p_version_id uuid;
BEGIN
    SELECT d.id INTO p_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid AND d.process_code='P002' AND d.enabled AND not d.is_deleted
     ORDER BY d.created_at,d.id LIMIT 1;

    IF p_definition_id IS NULL THEN
        p_definition_id := gen_random_uuid();
        INSERT INTO workflow.wf_definition(
            id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_at,updated_at,is_deleted)
        VALUES (p_definition_id,'${sjg_tenant_id}'::uuid,'P002','权限申请、复核与回收','全员公共能力','iam','permission_request',true,now(),now(),false);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_version v
         WHERE v.tenant_id='${sjg_tenant_id}'::uuid AND v.definition_id=p_definition_id
           AND v.status='PUBLISHED' AND not v.is_deleted
    ) THEN
        p_version_id := gen_random_uuid();
        -- Build the graph while the version is mutable. V100 intentionally makes
        -- every node/transition of a PUBLISHED version immutable.
        INSERT INTO workflow.wf_version(
            id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_at,updated_at,is_deleted)
        VALUES (
            p_version_id,'${sjg_tenant_id}'::uuid,p_definition_id,1,'DRAFT',NULL,
            jsonb_build_object('processCode','P002','source','PHASE09_SOURCE_CONTRACT','states',
                jsonb_build_array('S02','S03','S04','S05','S06','S07','S08','END')),
            'phase09-p002-source-flow-v1',now(),now(),false
        );

        INSERT INTO workflow.wf_node(
            id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','个人/项目补充授权申请','START',NULL,20,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','业务负责人确认','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,30,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','数据责任人复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,40,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','高风险权限审批','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"reviewCandidateIds"}'::jsonb,50,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','权限生效','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"executionCandidateIds"}'::jsonb,60,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','定期复核','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"lifecycleCandidateIds"}'::jsonb,70,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','到期/调岗/离职回收','TASK','{"resolver":"CONTEXT_EMPLOYEE_IDS","field":"lifecycleCandidateIds"}'::jsonb,80,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'END','已关闭','END',NULL,90,now(),now(),false);

        INSERT INTO workflow.wf_transition(
            id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted)
        VALUES
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S02','SUBMIT','S03',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','APPROVE','S04',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S03','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','APPROVE_STANDARD','S06',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','APPROVE_HIGH','S05',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S04','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','APPROVE','S06',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S05','REJECT','END',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S06','EXECUTE','S07',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','KEEP','S07',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','REVOKE_REQUEST','S08',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S07','AUTO_EXPIRE','S08',NULL,false,now(),now(),false),
          (gen_random_uuid(),'${sjg_tenant_id}'::uuid,p_version_id,'S08','REVOKE','END',NULL,false,now(),now(),false);

        -- Publish only after the complete graph exists. Once this UPDATE succeeds,
        -- the existing V100 guards make the version and its graph immutable.
        UPDATE workflow.wf_version
           SET status='PUBLISHED',effective_at=now(),updated_at=now()
         WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND not is_deleted;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'P002 workflow version could not be published' USING ERRCODE = '55000';
        END IF;
    END IF;
END
$$;

RESET ROLE;