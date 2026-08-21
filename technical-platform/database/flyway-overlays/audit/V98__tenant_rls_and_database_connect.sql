-- PHASE-03 tenant RLS + immutable audit database-connect security overlay for sjg_audit.
REVOKE CONNECT, TEMPORARY ON DATABASE sjg_audit FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE sjg_audit TO sjg_owner;
GRANT CONNECT ON DATABASE sjg_audit TO sjg_migration, sjg_audit_writer, sjg_auditor;

SET ROLE sjg_owner;
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT DISTINCT c.table_schema, c.table_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema AND t.table_name = c.table_name
    WHERE c.column_name = 'tenant_id'
      AND t.table_type = 'BASE TABLE'
      AND c.table_schema NOT IN ('pg_catalog', 'information_schema', 'public')
  LOOP
    EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', r.table_schema, r.table_name);
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies p
      WHERE p.schemaname = r.table_schema AND p.tablename = r.table_name
    ) THEN
      EXECUTE format(
        'CREATE POLICY p_tenant_baseline ON %I.%I USING (tenant_id = current_setting(''app.tenant_id'', true)::uuid) WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
        r.table_schema, r.table_name
      );
    END IF;
  END LOOP;
END
$$;
RESET ROLE;
