-- PHASE-10 formal-gate repair: preserve published history while making employee self-service nodes executable.
SET ROLE sjg_owner;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname='ck_p008_actual_leave_window'
  ) THEN
    ALTER TABLE attendance.leave_request
      ADD CONSTRAINT ck_p008_actual_leave_window
      CHECK (
        actual_start_at IS NULL
        OR actual_end_at IS NULL
        OR actual_end_at >= actual_start_at
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname='ck_p008_return_after_leave_start'
  ) THEN
    ALTER TABLE attendance.leave_request
      ADD CONSTRAINT ck_p008_return_after_leave_start
      CHECK (
        leave_started_at IS NULL
        OR returned_at IS NULL
        OR returned_at >= leave_started_at
      );
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname='ck_p009_timeoff_ledger_type'
  ) THEN
    ALTER TABLE attendance.overtime_request_item
      ADD CONSTRAINT ck_p009_timeoff_ledger_type
      CHECK (
        field_code <> 'TIME_OFF_LEDGER'
        OR item_key='GRANT'
      );
  END IF;
END
$$;

DO $$
DECLARE
  cfg RECORD;
  v_definition_id UUID;
  v_source_version_id UUID;
  v_target_version_id UUID;
  v_next_version_no INTEGER;
BEGIN
  FOR cfg IN
    SELECT *
    FROM (VALUES
      ('P007', ARRAY['S05','S06']::TEXT[], 'phase10-p007-v2-self-service'),
      ('P008', ARRAY['S03','S07','S08']::TEXT[], 'phase10-p008-v2-self-service'),
      ('P009', ARRAY['S04']::TEXT[], 'phase10-p009-v2-self-service')
    ) AS configuration(process_code,self_service_nodes,checksum)
  LOOP
    SELECT d.id
      INTO v_definition_id
      FROM workflow.wf_definition d
     WHERE d.tenant_id='${sjg_tenant_id}'::uuid
       AND d.process_code=cfg.process_code
       AND d.enabled
       AND NOT d.is_deleted
     ORDER BY d.created_at,d.id
     LIMIT 1;

    IF v_definition_id IS NULL THEN
      RAISE EXCEPTION 'PHASE-10 workflow definition missing for %',cfg.process_code;
    END IF;

    IF EXISTS (
      SELECT 1
        FROM workflow.wf_version v
       WHERE v.tenant_id='${sjg_tenant_id}'::uuid
         AND v.definition_id=v_definition_id
         AND v.checksum=cfg.checksum
         AND v.status='PUBLISHED'
         AND NOT v.is_deleted
    ) THEN
      CONTINUE;
    END IF;

    SELECT v.id
      INTO v_source_version_id
      FROM workflow.wf_version v
     WHERE v.tenant_id='${sjg_tenant_id}'::uuid
       AND v.definition_id=v_definition_id
       AND v.status='PUBLISHED'
       AND NOT v.is_deleted
     ORDER BY v.version_no DESC,v.created_at DESC,v.id DESC
     LIMIT 1;

    IF v_source_version_id IS NULL THEN
      RAISE EXCEPTION 'PHASE-10 published workflow version missing for %',cfg.process_code;
    END IF;

    SELECT COALESCE(MAX(v.version_no),0)+1
      INTO v_next_version_no
      FROM workflow.wf_version v
     WHERE v.tenant_id='${sjg_tenant_id}'::uuid
       AND v.definition_id=v_definition_id
       AND NOT v.is_deleted;

    v_target_version_id:=gen_random_uuid();

    INSERT INTO workflow.wf_version(
      id,tenant_id,definition_id,version_no,status,
      definition_json,checksum,created_at,updated_at,is_deleted
    )
    SELECT
      v_target_version_id,
      v.tenant_id,
      v.definition_id,
      v_next_version_no,
      'DRAFT',
      v.definition_json || jsonb_build_object(
        'phase10SelfServiceNodes',to_jsonb(cfg.self_service_nodes),
        'supersedesVersionId',v_source_version_id::text
      ),
      cfg.checksum,
      now(),now(),false
    FROM workflow.wf_version v
    WHERE v.tenant_id='${sjg_tenant_id}'::uuid
      AND v.id=v_source_version_id;

    INSERT INTO workflow.wf_node(
      id,tenant_id,version_id,node_code,node_name,node_type,
      actor_rule,sort_no,created_at,updated_at,is_deleted
    )
    SELECT
      gen_random_uuid(),
      n.tenant_id,
      v_target_version_id,
      n.node_code,
      n.node_name,
      n.node_type,
      CASE
        WHEN n.node_code=ANY(cfg.self_service_nodes)
        THEN jsonb_set(
          COALESCE(n.actor_rule,'{}'::jsonb),
          '{allowInitiator}',
          'true'::jsonb,
          true
        )
        ELSE n.actor_rule
      END,
      n.sort_no,
      now(),now(),false
    FROM workflow.wf_node n
    WHERE n.tenant_id='${sjg_tenant_id}'::uuid
      AND n.version_id=v_source_version_id
      AND NOT n.is_deleted;

    INSERT INTO workflow.wf_transition(
      id,tenant_id,version_id,from_node_code,action_code,to_node_code,
      condition_expr,is_rollback,created_at,updated_at,is_deleted
    )
    SELECT
      gen_random_uuid(),
      t.tenant_id,
      v_target_version_id,
      t.from_node_code,
      t.action_code,
      t.to_node_code,
      t.condition_expr,
      t.is_rollback,
      now(),now(),false
    FROM workflow.wf_transition t
    WHERE t.tenant_id='${sjg_tenant_id}'::uuid
      AND t.version_id=v_source_version_id
      AND NOT t.is_deleted;

    UPDATE workflow.wf_version
       SET status='PUBLISHED',effective_at=now(),updated_at=now()
     WHERE tenant_id='${sjg_tenant_id}'::uuid
       AND id=v_target_version_id
       AND status='DRAFT';
  END LOOP;
END
$$;

RESET ROLE;
