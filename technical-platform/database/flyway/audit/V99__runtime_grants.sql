-- PHASE-03 immutable audit runtime contract.
SET ROLE sjg_owner;
GRANT USAGE ON SCHEMA audit TO sjg_audit_writer, sjg_auditor;
REVOKE CREATE ON SCHEMA audit FROM sjg_audit_writer, sjg_auditor;
REVOKE UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA audit FROM sjg_audit_writer, sjg_auditor, sjg_app;
GRANT INSERT, SELECT ON ALL TABLES IN SCHEMA audit TO sjg_audit_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA audit TO sjg_auditor;
ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA audit GRANT INSERT, SELECT ON TABLES TO sjg_audit_writer;
ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA audit GRANT SELECT ON TABLES TO sjg_auditor;
RESET ROLE;
