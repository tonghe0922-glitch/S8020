-- PHASE-03 analytics writer/reader contract. Business API roles receive no direct sjg_dw privileges.
SET ROLE sjg_owner;
GRANT USAGE ON SCHEMA analytics TO sjg_dw_writer, sjg_dw_reader;
REVOKE CREATE ON SCHEMA analytics FROM sjg_dw_writer, sjg_dw_reader;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA analytics TO sjg_dw_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO sjg_dw_reader;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA analytics TO sjg_dw_writer;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA analytics TO sjg_dw_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA analytics GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sjg_dw_writer;
ALTER DEFAULT PRIVILEGES FOR ROLE sjg_owner IN SCHEMA analytics GRANT SELECT ON TABLES TO sjg_dw_reader;
RESET ROLE;
