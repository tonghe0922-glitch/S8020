#!/bin/sh
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SELECT 'CREATE DATABASE sjg_oms OWNER sjg_owner'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sjg_oms')\gexec
SELECT 'CREATE DATABASE sjg_audit OWNER sjg_owner'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sjg_audit')\gexec
SELECT 'CREATE DATABASE sjg_dw OWNER sjg_owner'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'sjg_dw')\gexec
ALTER DATABASE sjg_oms OWNER TO sjg_owner;
ALTER DATABASE sjg_audit OWNER TO sjg_owner;
ALTER DATABASE sjg_dw OWNER TO sjg_owner;
SQL
