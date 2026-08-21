-- PHASE-05 canonical workflow runtime overlay.
-- This file intentionally does not modify generated Knowledge Base DDL.
-- It adds runtime invariants required by the corrected PHASE-05 DoD.
SET ROLE sjg_owner;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_version_definition_version
    ON workflow.wf_version (tenant_id, definition_id, version_no)
    WHERE NOT is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_node_version_code
    ON workflow.wf_node (tenant_id, version_id, node_code)
    WHERE NOT is_deleted;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_wf_transition_identity
    ON workflow.wf_transition (tenant_id, version_id, from_node_code, action_code, to_node_code)
    WHERE NOT is_deleted;

CREATE OR REPLACE FUNCTION workflow.guard_published_version_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'published workflow version is immutable: %', OLD.id USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_version_published_immutable ON workflow.wf_version;
CREATE TRIGGER trg_wf_version_published_immutable
BEFORE UPDATE OR DELETE ON workflow.wf_version
FOR EACH ROW EXECUTE FUNCTION workflow.guard_published_version_immutable();

CREATE OR REPLACE FUNCTION workflow.guard_published_graph_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_published boolean := false;
    new_published boolean := false;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        SELECT EXISTS (
            SELECT 1 FROM workflow.wf_version v
            WHERE v.id = OLD.version_id AND v.tenant_id = OLD.tenant_id
              AND NOT v.is_deleted AND v.status = 'PUBLISHED'
        ) INTO old_published;
    END IF;

    IF TG_OP <> 'DELETE' THEN
        SELECT EXISTS (
            SELECT 1 FROM workflow.wf_version v
            WHERE v.id = NEW.version_id AND v.tenant_id = NEW.tenant_id
              AND NOT v.is_deleted AND v.status = 'PUBLISHED'
        ) INTO new_published;
    END IF;

    IF old_published OR new_published THEN
        RAISE EXCEPTION 'published workflow graph is immutable' USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_wf_node_published_immutable ON workflow.wf_node;
CREATE TRIGGER trg_wf_node_published_immutable
BEFORE INSERT OR UPDATE OR DELETE ON workflow.wf_node
FOR EACH ROW EXECUTE FUNCTION workflow.guard_published_graph_immutable();

DROP TRIGGER IF EXISTS trg_wf_transition_published_immutable ON workflow.wf_transition;
CREATE TRIGGER trg_wf_transition_published_immutable
BEFORE INSERT OR UPDATE OR DELETE ON workflow.wf_transition
FOR EACH ROW EXECUTE FUNCTION workflow.guard_published_graph_immutable();

RESET ROLE;
