-- PHASE-03 tenant bootstrap for approved tenant-scoped seed migrations.
-- No default tenant identity is stored in Git. Deployment MUST provide all three Flyway placeholders:
--   sjg_tenant_id / sjg_tenant_code / sjg_tenant_name
-- The following values are deployment facts, not application-generated business defaults.

SET ROLE sjg_owner;
DO $$
DECLARE
  v_id uuid := '${sjg_tenant_id}'::uuid;
  v_code text := '${sjg_tenant_code}';
  v_name text := '${sjg_tenant_name}';
  existing_code text;
  existing_name text;
BEGIN
  IF btrim(v_code) = '' OR btrim(v_name) = '' THEN
    RAISE EXCEPTION 'sjg_tenant_code and sjg_tenant_name must be non-empty deployment facts';
  END IF;

  SELECT tenant_code, tenant_name
    INTO existing_code, existing_name
    FROM core.tenant
   WHERE id = v_id;

  IF NOT FOUND THEN
    INSERT INTO core.tenant(id, tenant_code, tenant_name)
    VALUES (v_id, v_code, v_name);
  ELSIF existing_code IS DISTINCT FROM v_code OR existing_name IS DISTINCT FROM v_name THEN
    RAISE EXCEPTION 'tenant bootstrap mismatch for id %: existing code/name do not match supplied deployment facts', v_id;
  END IF;
END
$$;
RESET ROLE;
