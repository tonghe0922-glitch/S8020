-- PHASE-05 canonical form-version/submission integrity overlay.
SET ROLE sjg_owner;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_form_definition_version
    ON workflow.wf_form_definition (tenant_id, form_code, process_code, node_code, version_no)
    WHERE NOT is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_submission_value_field
    ON workflow.wf_submission_value (tenant_id, submission_id, field_code)
    WHERE NOT is_deleted;

CREATE OR REPLACE FUNCTION workflow.guard_published_form_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.enabled THEN
            RAISE EXCEPTION 'published workflow form version is immutable: %', OLD.id USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.enabled THEN
        RAISE EXCEPTION 'published workflow form version is immutable: %', OLD.id USING ERRCODE = '55000';
    END IF;

    IF NOT OLD.enabled AND NEW.enabled AND (
        NEW.form_code IS DISTINCT FROM OLD.form_code OR
        NEW.form_name IS DISTINCT FROM OLD.form_name OR
        NEW.process_code IS DISTINCT FROM OLD.process_code OR
        NEW.node_code IS DISTINCT FROM OLD.node_code OR
        NEW.version_no IS DISTINCT FROM OLD.version_no OR
        NEW.field_schema IS DISTINCT FROM OLD.field_schema OR
        NEW.layout_schema IS DISTINCT FROM OLD.layout_schema OR
        NEW.validation_schema IS DISTINCT FROM OLD.validation_schema OR
        NEW.visibility_matrix IS DISTINCT FROM OLD.visibility_matrix OR
        NEW.edit_matrix IS DISTINCT FROM OLD.edit_matrix OR
        NEW.is_deleted IS DISTINCT FROM OLD.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow form publish may only enable the frozen draft version' USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_form_published_immutable ON workflow.wf_form_definition;
CREATE TRIGGER trg_wf_form_published_immutable
BEFORE UPDATE OR DELETE ON workflow.wf_form_definition
FOR EACH ROW EXECUTE FUNCTION workflow.guard_published_form_immutable();

CREATE OR REPLACE FUNCTION workflow.guard_wf_submission_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    form_process varchar(16);
    form_node varchar(32);
    actual_form_version integer;
    form_enabled boolean;
    instance_process varchar(16);
    instance_node varchar(32);
    task_instance uuid;
    task_node varchar(32);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.instance_id IS DISTINCT FROM OLD.instance_id OR
           NEW.task_id IS DISTINCT FROM OLD.task_id OR
           NEW.form_definition_id IS DISTINCT FROM OLD.form_definition_id OR
           NEW.form_version IS DISTINCT FROM OLD.form_version OR
           NEW.submitter_id IS DISTINCT FROM OLD.submitter_id OR
           NEW.submitted_at IS DISTINCT FROM OLD.submitted_at OR
           NEW.content_hash IS DISTINCT FROM OLD.content_hash OR
           NEW.submission_no IS DISTINCT FROM OLD.submission_no THEN
            RAISE EXCEPTION 'workflow submission version/binding is immutable' USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    SELECT f.process_code,f.node_code,f.version_no,f.enabled
      INTO form_process,form_node,actual_form_version,form_enabled
    FROM workflow.wf_form_definition f
    WHERE f.tenant_id=NEW.tenant_id AND f.id=NEW.form_definition_id AND NOT f.is_deleted;

    IF actual_form_version IS NULL OR NOT form_enabled THEN
        RAISE EXCEPTION 'workflow submission requires a published form version' USING ERRCODE = '23514';
    END IF;
    IF NEW.form_version <> actual_form_version THEN
        RAISE EXCEPTION 'workflow submission form_version does not match form definition' USING ERRCODE = '23514';
    END IF;

    SELECT i.process_code,i.current_node_code
      INTO instance_process,instance_node
    FROM workflow.wf_instance i
    WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.instance_id AND NOT i.is_deleted;
    IF instance_process IS NULL THEN
        RAISE EXCEPTION 'workflow submission references missing instance' USING ERRCODE = '23503';
    END IF;
    IF form_process <> instance_process THEN
        RAISE EXCEPTION 'workflow form process does not match instance process' USING ERRCODE = '23514';
    END IF;

    IF NEW.task_id IS NOT NULL THEN
        SELECT t.instance_id,t.node_code INTO task_instance,task_node
        FROM workflow.wf_task t
        WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.task_id AND NOT t.is_deleted;
        IF task_instance IS NULL OR task_instance <> NEW.instance_id THEN
            RAISE EXCEPTION 'workflow submission task does not belong to instance' USING ERRCODE = '23514';
        END IF;
        IF form_node <> task_node THEN
            RAISE EXCEPTION 'workflow form node does not match task node' USING ERRCODE = '23514';
        END IF;
    ELSIF form_node <> instance_node THEN
        RAISE EXCEPTION 'workflow form node does not match current instance node' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_submission_binding ON workflow.wf_submission;
CREATE TRIGGER trg_wf_submission_binding
BEFORE INSERT OR UPDATE ON workflow.wf_submission
FOR EACH ROW EXECUTE FUNCTION workflow.guard_wf_submission_binding();

CREATE OR REPLACE FUNCTION workflow.guard_wf_submission_value_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'submitted workflow field values are immutable; create a new submission version' USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM workflow.wf_submission s
        WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.submission_id AND NOT s.is_deleted
    ) THEN
        RAISE EXCEPTION 'workflow field value references missing submission' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_submission_value_immutable ON workflow.wf_submission_value;
CREATE TRIGGER trg_wf_submission_value_immutable
BEFORE INSERT OR UPDATE OR DELETE ON workflow.wf_submission_value
FOR EACH ROW EXECUTE FUNCTION workflow.guard_wf_submission_value_immutable();

RESET ROLE;
