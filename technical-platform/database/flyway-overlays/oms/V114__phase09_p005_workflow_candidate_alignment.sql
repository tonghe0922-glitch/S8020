-- PHASE-09 / P005: publish workflow v2 so explicitly non-approval manager tasks may be executed by the initiator.
-- Global WorkflowCandidateResolver remains fail-closed: initiator is excluded unless actor_rule.allowInitiator=true.
-- P005 source/service contract keeps the publisher as an eligible center manager for S02/S03/S08/S09/S10.
SET ROLE sjg_owner;

DO $$
DECLARE
    p_definition_id uuid;
    p_source_version_id uuid;
    p_version_id uuid;
    p_version_no integer;
BEGIN
    SELECT d.id INTO p_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid
       AND d.process_code='P005' AND d.enabled AND NOT d.is_deleted
     ORDER BY d.created_at,d.id LIMIT 1;
    IF p_definition_id IS NULL THEN
        RAISE EXCEPTION 'P005 workflow definition is missing before v2 candidate alignment' USING ERRCODE='55000';
    END IF;

    IF EXISTS (
        SELECT 1 FROM workflow.wf_version v
         WHERE v.tenant_id='${sjg_tenant_id}'::uuid
           AND v.definition_id=p_definition_id
           AND v.checksum='phase09-p005-source-flow-v2-allow-initiator-manager-tasks'
           AND v.status='PUBLISHED' AND NOT v.is_deleted
    ) THEN
        RETURN;
    END IF;

    SELECT v.id INTO p_source_version_id
      FROM workflow.wf_version v
     WHERE v.tenant_id='${sjg_tenant_id}'::uuid
       AND v.definition_id=p_definition_id
       AND v.status='PUBLISHED' AND NOT v.is_deleted
     ORDER BY v.version_no DESC,v.effective_at DESC NULLS LAST,v.created_at DESC
     LIMIT 1;
    IF p_source_version_id IS NULL THEN
        RAISE EXCEPTION 'P005 published workflow source version is missing before v2 candidate alignment' USING ERRCODE='55000';
    END IF;

    SELECT coalesce(max(v.version_no),0)+1 INTO p_version_no
      FROM workflow.wf_version v
     WHERE v.tenant_id='${sjg_tenant_id}'::uuid AND v.definition_id=p_definition_id AND NOT v.is_deleted;

    p_version_id := gen_random_uuid();
    INSERT INTO workflow.wf_version(
        id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_at,updated_at,is_deleted)
    SELECT p_version_id,v.tenant_id,v.definition_id,p_version_no,'DRAFT',NULL,
           coalesce(v.definition_json,'{}'::jsonb) || jsonb_build_object(
               'candidatePolicy','P005 manager tasks explicitly allow initiator; global resolver default remains self-excluding'),
           'phase09-p005-source-flow-v2-allow-initiator-manager-tasks',now(),now(),false
      FROM workflow.wf_version v
     WHERE v.tenant_id='${sjg_tenant_id}'::uuid AND v.id=p_source_version_id;

    INSERT INTO workflow.wf_node(
        id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sort_no,created_at,updated_at,is_deleted)
    SELECT gen_random_uuid(),n.tenant_id,p_version_id,n.node_code,n.node_name,n.node_type,
           CASE WHEN n.node_code IN ('S02','S03','S08','S09','S10') THEN
               jsonb_set(coalesce(n.actor_rule,'{}'::jsonb),'{allowInitiator}','true'::jsonb,true)
           ELSE n.actor_rule END,
           n.sort_no,now(),now(),false
      FROM workflow.wf_node n
     WHERE n.tenant_id='${sjg_tenant_id}'::uuid AND n.version_id=p_source_version_id AND NOT n.is_deleted
     ORDER BY n.sort_no,n.node_code;

    INSERT INTO workflow.wf_transition(
        id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_at,updated_at,is_deleted)
    SELECT gen_random_uuid(),t.tenant_id,p_version_id,t.from_node_code,t.action_code,t.to_node_code,
           t.condition_expr,t.is_rollback,now(),now(),false
      FROM workflow.wf_transition t
     WHERE t.tenant_id='${sjg_tenant_id}'::uuid AND t.version_id=p_source_version_id AND NOT t.is_deleted
     ORDER BY t.created_at,t.id;

    UPDATE workflow.wf_version
       SET status='PUBLISHED',effective_at=now(),updated_at=now()
     WHERE tenant_id='${sjg_tenant_id}'::uuid AND id=p_version_id AND status='DRAFT' AND NOT is_deleted;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P005 workflow v2 could not be published' USING ERRCODE='55000';
    END IF;
END
$$;

RESET ROLE;
