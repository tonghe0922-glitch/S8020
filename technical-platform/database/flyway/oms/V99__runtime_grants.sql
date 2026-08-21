-- PHASE-03 least-privilege runtime grants for sjg_oms.
SET ROLE sjg_owner;
DO $$
DECLARE s record;
BEGIN
  FOR s IN
    SELECT nspname FROM pg_namespace
    WHERE nspname NOT IN ('pg_catalog', 'information_schema', 'public')
      AND nspname NOT LIKE 'pg_toast%'
      AND nspname NOT LIKE 'pg_temp_%'
  LOOP
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO sjg_api_runtime, sjg_worker_runtime', s.nspname);
    EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM sjg_api_runtime, sjg_worker_runtime', s.nspname);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO sjg_api_runtime, sjg_worker_runtime', s.nspname);
    EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO sjg_api_runtime, sjg_worker_runtime', s.nspname);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sjg_api_runtime, sjg_worker_runtime', s.nspname);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA %I GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO sjg_api_runtime, sjg_worker_runtime', s.nspname);
  END LOOP;
END
$$;
RESET ROLE;
