-- PHASE-09 / P001: TOTP MFA credential runtime storage.
SET ROLE sjg_owner;
CREATE TABLE IF NOT EXISTS iam.mfa_credential (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    method varchar(16) NOT NULL,
    secret_cipher bytea NOT NULL,
    status varchar(16) NOT NULL,
    version_no integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    disabled_at timestamptz,
    CONSTRAINT uq_mfa_credential_user_method UNIQUE (tenant_id, user_id, method),
    CONSTRAINT ck_mfa_credential_method CHECK (method IN ('TOTP')),
    CONSTRAINT ck_mfa_credential_status CHECK (status IN ('PENDING','ACTIVE','DISABLED'))
);
CREATE INDEX IF NOT EXISTS idx_mfa_credential_user ON iam.mfa_credential(tenant_id,user_id,status);
ALTER TABLE iam.mfa_credential ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS p_tenant_baseline ON iam.mfa_credential;
CREATE POLICY p_tenant_baseline ON iam.mfa_credential
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.mfa_credential TO sjg_api_runtime;
RESET ROLE;
