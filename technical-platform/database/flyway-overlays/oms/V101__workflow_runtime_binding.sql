-- PHASE-05 canonical workflow runtime binding and history integrity.
SET ROLE sjg_owner;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_action_log_request
    ON workflow.wf_action_log (tenant_id, request_id)
    WHERE request_id IS NOT NULL AND NOT is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_instance_no
    ON workflow.wf_instance (tenant_id, instance_no)
    WHERE NOT is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_task_no
    ON workflow.wf_task (tenant_id, task_no)
    WHERE NOT is_deleted;

CREATE OR REPLACE FUNCTION workflow.guard_wf_instance_version_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    binding_ok boolean;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.definition_id IS DISTINCT FROM OLD.definition_id OR
        NEW.version_id IS DISTINCT FROM OLD.version_id OR
        NEW.process_code IS DISTINCT FROM OLD.process_code
    ) THEN
        RAISE EXCEPTION 'workflow instance version binding is immutable' USING ERRCODE = '55000';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM workflow.wf_version v
        JOIN workflow.wf_definition d
          ON d.tenant_id = v.tenant_id
         AND d.id = v.definition_id
         AND NOT d.is_deleted
         AND d.enabled
        WHERE v.tenant_id = NEW.tenant_id
          AND v.id = NEW.version_id
          AND v.definition_id = NEW.definition_id
          AND v.status = 'PUBLISHED'
          AND NOT v.is_deleted
          AND (v.effective_at IS NULL OR v.effective_at <= now())
          AND d.process_code = NEW.process_code
    ) INTO binding_ok;

    IF NOT binding_ok THEN
        RAISE EXCEPTION 'workflow instance must bind an effective published version/definition/process'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.current_node_code IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM workflow.wf_node n
        WHERE n.tenant_id = NEW.tenant_id
          AND n.version_id = NEW.version_id
          AND n.node_code = NEW.current_node_code
          AND NOT n.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow instance current node is outside the bound version'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_instance_version_binding ON workflow.wf_instance;
CREATE TRIGGER trg_wf_instance_version_binding
BEFORE INSERT OR UPDATE ON workflow.wf_instance
FOR EACH ROW EXECUTE FUNCTION workflow.guard_wf_instance_version_binding();

CREATE OR REPLACE FUNCTION workflow.guard_wf_task_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    instance_version uuid;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.instance_id IS DISTINCT FROM OLD.instance_id OR
        NEW.node_code IS DISTINCT FROM OLD.node_code
    ) THEN
        RAISE EXCEPTION 'workflow task instance/node binding is immutable' USING ERRCODE = '55000';
    END IF;

    SELECT i.version_id INTO instance_version
    FROM workflow.wf_instance i
    WHERE i.tenant_id = NEW.tenant_id
      AND i.id = NEW.instance_id
      AND NOT i.is_deleted;

    IF instance_version IS NULL THEN
        RAISE EXCEPTION 'workflow task references missing instance' USING ERRCODE = '23503';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_node n
        WHERE n.tenant_id = NEW.tenant_id
          AND n.version_id = instance_version
          AND n.node_code = NEW.node_code
          AND NOT n.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow task node is outside instance bound version' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_task_binding ON workflow.wf_task;
CREATE TRIGGER trg_wf_task_binding
BEFORE INSERT OR UPDATE ON workflow.wf_task
FOR EACH ROW EXECUTE FUNCTION workflow.guard_wf_task_binding();

CREATE OR REPLACE FUNCTION workflow.guard_wf_action_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_instance i
        WHERE i.tenant_id = NEW.tenant_id
          AND i.id = NEW.instance_id
          AND NOT i.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow action references missing instance' USING ERRCODE = '23503';
    END IF;

    IF NEW.task_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM workflow.wf_task t
        WHERE t.tenant_id = NEW.tenant_id
          AND t.id = NEW.task_id
          AND t.instance_id = NEW.instance_id
          AND NOT t.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow action task does not belong to instance' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_action_binding ON workflow.wf_action_log;
CREATE TRIGGER trg_wf_action_binding
BEFORE INSERT OR UPDATE ON workflow.wf_action_log
FOR EACH ROW EXECUTE FUNCTION workflow.guard_wf_action_binding();

RESET ROLE;
