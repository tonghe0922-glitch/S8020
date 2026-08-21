SET ROLE sjg_owner;
SET search_path TO iam, public;

CREATE OR REPLACE FUNCTION iam.classify_permission_request_revoke_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.grant_status = 'REVOKED'
       AND OLD.grant_status IS DISTINCT FROM NEW.grant_status
       AND NEW.revoke_source IS NULL THEN
        NEW.revoke_source := CASE WHEN NEW.revoked_by IS NULL THEN 'AUTO_EXPIRE' ELSE 'MANUAL' END;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_permission_request_grant_revoke_source ON iam.permission_request_grant;
CREATE TRIGGER trg_permission_request_grant_revoke_source
BEFORE UPDATE OF grant_status,revoke_source,revoked_by
ON iam.permission_request_grant
FOR EACH ROW
EXECUTE FUNCTION iam.classify_permission_request_revoke_source();

COMMENT ON FUNCTION iam.classify_permission_request_revoke_source() IS
    'P002回收来源兜底｜人工回收记录MANUAL；无员工操作人的系统到期回收记录AUTO_EXPIRE；不伪造员工身份';

RESET ROLE;
