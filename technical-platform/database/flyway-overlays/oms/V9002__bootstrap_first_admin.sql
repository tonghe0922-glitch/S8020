-- Controlled first-administrator bootstrap for a brand-new S8020 deployment.
-- Sensitive values are supplied by the migration runner as per-connection settings; no plaintext password is stored in Git.
-- Direct superuser-only test harnesses may apply the version while intentionally skipping the deployment seed.
SET ROLE sjg_owner;

DO $$
DECLARE
    v_tenant_id uuid := '${sjg_tenant_id}'::uuid;
    v_login_name text := nullif(btrim(current_setting('sjg.bootstrap.admin_login_name', true)), '');
    v_password_hash text := nullif(current_setting('sjg.bootstrap.admin_password_hash', true), '');
    v_employee_no text := nullif(btrim(current_setting('sjg.bootstrap.admin_employee_no', true)), '');

    v_org_id uuid := md5(v_tenant_id::text || ':bootstrap:org')::uuid;
    v_position_id uuid := md5(v_tenant_id::text || ':bootstrap:position')::uuid;
    v_employee_id uuid := md5(v_tenant_id::text || ':bootstrap:employee')::uuid;
    v_appointment_id uuid := md5(v_tenant_id::text || ':bootstrap:appointment')::uuid;
    v_user_id uuid := md5(v_tenant_id::text || ':bootstrap:user')::uuid;
    v_identity_id uuid := md5(v_tenant_id::text || ':bootstrap:identity')::uuid;
    v_scope_id uuid := md5(v_tenant_id::text || ':bootstrap:scope')::uuid;
    v_role_id uuid := md5(v_tenant_id::text || ':bootstrap:role')::uuid;
    v_permission_id uuid;
    v_permission record;
    v_existing_hash text;
BEGIN
    IF v_login_name IS NULL OR v_employee_no IS NULL OR v_password_hash IS NULL THEN
        IF session_user IN ('postgres', 'sjg_bootstrap') THEN
            RAISE NOTICE 'first administrator seed skipped for direct superuser test migration';
            RETURN;
        END IF;
        RAISE EXCEPTION 'first administrator bootstrap settings are missing; run scripts/database/migrate.sh';
    END IF;
    IF v_password_hash !~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'sjg_admin_password_hash must be a BCrypt cost=12 hash';
    END IF;

    PERFORM set_config('app.tenant_id', v_tenant_id::text, true);

    INSERT INTO org.organization(
        id, tenant_id, org_code, org_name, org_type, path, status,
        created_at, updated_at, is_deleted)
    VALUES (
        v_org_id, v_tenant_id, 'S8020-PLATFORM', '平台管理中心', 'CENTER',
        's8020_platform'::ltree, 'ACTIVE', now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_org_id
      FROM org.organization
     WHERE tenant_id = v_tenant_id
       AND org_code = 'S8020-PLATFORM'
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_org_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap platform organization';
    END IF;

    INSERT INTO org.position(
        id, tenant_id, position_code, position_name, org_id, status,
        created_at, updated_at, is_deleted)
    VALUES (
        v_position_id, v_tenant_id, 'S8020-ADMIN', '平台管理员', v_org_id, 'ACTIVE',
        now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_position_id
      FROM org.position
     WHERE tenant_id = v_tenant_id
       AND position_code = 'S8020-ADMIN'
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_position_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap administrator position';
    END IF;

    INSERT INTO org.employee(
        id, tenant_id, employee_no, person_name, employment_status, hire_date,
        primary_org_id, primary_position_id, created_at, updated_at, is_deleted)
    VALUES (
        v_employee_id, v_tenant_id, v_employee_no, '首个管理员', 'ACTIVE', current_date,
        v_org_id, v_position_id, now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_employee_id
      FROM org.employee
     WHERE tenant_id = v_tenant_id
       AND employee_no = v_employee_no
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_employee_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap administrator employee';
    END IF;

    INSERT INTO org.employee_position(
        id, tenant_id, employee_id, position_id, org_id, is_primary,
        effective_start_date, status, created_at, updated_at, is_deleted)
    VALUES (
        v_appointment_id, v_tenant_id, v_employee_id, v_position_id, v_org_id, true,
        current_date, 'ACTIVE', now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_appointment_id
      FROM org.employee_position
     WHERE tenant_id = v_tenant_id
       AND employee_id = v_employee_id
       AND org_id = v_org_id
       AND position_id = v_position_id
       AND status = 'ACTIVE'
       AND effective_start_date <= current_date
       AND (effective_end_date IS NULL OR effective_end_date >= current_date)
       AND NOT is_deleted
     ORDER BY is_primary DESC, effective_start_date, id
     LIMIT 1;
    IF v_appointment_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap active administrator appointment';
    END IF;

    INSERT INTO iam.user_account(
        id, tenant_id, login_name, password_hash, status, mfa_level,
        created_at, updated_at, is_deleted)
    VALUES (
        v_user_id, v_tenant_id, v_login_name, v_password_hash, 'ACTIVE', 0,
        now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id, password_hash
      INTO v_user_id, v_existing_hash
      FROM iam.user_account
     WHERE tenant_id = v_tenant_id
       AND login_name = v_login_name
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap administrator account';
    END IF;
    IF v_existing_hash IS NULL OR v_existing_hash = '' THEN
        RAISE EXCEPTION 'bootstrap administrator account has no password hash';
    END IF;

    INSERT INTO iam.user_identity(
        id, tenant_id, user_id, employee_id, identity_type, identity_name,
        org_id, position_id, is_primary, effective_start_at,
        created_at, updated_at, is_deleted)
    VALUES (
        v_identity_id, v_tenant_id, v_user_id, v_employee_id, 'EMPLOYEE', '首个管理员',
        v_org_id, v_position_id, true, now(), now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_identity_id
      FROM iam.user_identity
     WHERE tenant_id = v_tenant_id
       AND user_id = v_user_id
       AND employee_id = v_employee_id
       AND org_id = v_org_id
       AND position_id = v_position_id
       AND effective_start_at <= now()
       AND (effective_end_at IS NULL OR effective_end_at > now())
       AND NOT is_deleted
     ORDER BY is_primary DESC, effective_start_at, id
     LIMIT 1;
    IF v_identity_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap active administrator identity';
    END IF;

    INSERT INTO iam.data_scope_rule(
        id, tenant_id, scope_code, scope_name, rule_expr, enabled,
        created_at, updated_at, is_deleted)
    VALUES (
        v_scope_id, v_tenant_id, 'S8020_ALL', '全租户数据', '{"type":"ALL"}'::jsonb, true,
        now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_scope_id
      FROM iam.data_scope_rule
     WHERE tenant_id = v_tenant_id
       AND scope_code = 'S8020_ALL'
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_scope_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap administrator data scope';
    END IF;

    INSERT INTO iam.role(
        id, tenant_id, role_code, role_name, role_type, data_scope_code, enabled,
        created_at, updated_at, is_deleted)
    VALUES (
        v_role_id, v_tenant_id, 'S8020_BOOTSTRAP_ADMIN', '首个管理员', 'PLATFORM',
        'S8020_ALL', true, now(), now(), false)
    ON CONFLICT DO NOTHING;

    SELECT id
      INTO v_role_id
      FROM iam.role
     WHERE tenant_id = v_tenant_id
       AND role_code = 'S8020_BOOTSTRAP_ADMIN'
       AND NOT is_deleted
     ORDER BY created_at, id
     LIMIT 1;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'failed to bootstrap administrator role';
    END IF;

    FOR v_permission IN
        SELECT *
          FROM (VALUES
              ('platform.session.read', '会话信息查看', 'PLATFORM_SESSION', 'READ', 'NORMAL'),
              ('platform.session.switch', '身份切换', 'PLATFORM_SESSION', 'SWITCH', 'HIGH'),
              ('platform.session.logout', '会话退出', 'PLATFORM_SESSION', 'LOGOUT', 'NORMAL'),
              ('platform.stepup.issue', '增强认证票据签发', 'PLATFORM_SECURITY', 'ISSUE', 'HIGH')
          ) AS permission(permission_code, permission_name, resource_type, action_code, risk_level)
    LOOP
        v_permission_id := md5(
            v_tenant_id::text || ':bootstrap:permission:' || v_permission.permission_code
        )::uuid;

        INSERT INTO iam.permission(
            id, tenant_id, permission_code, permission_name, resource_type,
            action_code, risk_level, created_at, updated_at, is_deleted)
        VALUES (
            v_permission_id, v_tenant_id, v_permission.permission_code,
            v_permission.permission_name, v_permission.resource_type,
            v_permission.action_code, v_permission.risk_level,
            now(), now(), false)
        ON CONFLICT DO NOTHING;

        SELECT id
          INTO v_permission_id
          FROM iam.permission
         WHERE tenant_id = v_tenant_id
           AND permission_code = v_permission.permission_code
           AND NOT is_deleted
         ORDER BY created_at, id
         LIMIT 1;
        IF v_permission_id IS NULL THEN
            RAISE EXCEPTION 'failed to bootstrap permission %', v_permission.permission_code;
        END IF;

        INSERT INTO iam.role_permission(
            id, tenant_id, role_id, permission_id,
            created_at, updated_at, is_deleted)
        VALUES (
            md5(v_role_id::text || ':' || v_permission_id::text)::uuid,
            v_tenant_id, v_role_id, v_permission_id,
            now(), now(), false)
        ON CONFLICT DO NOTHING;
    END LOOP;

    INSERT INTO iam.user_role(
        id, tenant_id, user_id, identity_id, role_id,
        effective_start_at, grant_source, created_at, updated_at, is_deleted)
    VALUES (
        md5(v_user_id::text || ':' || v_identity_id::text || ':' || v_role_id::text)::uuid,
        v_tenant_id, v_user_id, v_identity_id, v_role_id,
        now(), 'BOOTSTRAP', now(), now(), false)
    ON CONFLICT DO NOTHING;

    IF NOT EXISTS (
        SELECT 1
          FROM iam.user_account account
          JOIN iam.user_identity identity
            ON identity.tenant_id = account.tenant_id
           AND identity.user_id = account.id
           AND identity.id = v_identity_id
           AND NOT identity.is_deleted
          JOIN org.employee_position appointment
            ON appointment.tenant_id = identity.tenant_id
           AND appointment.employee_id = identity.employee_id
           AND appointment.org_id = identity.org_id
           AND appointment.position_id = identity.position_id
           AND appointment.status = 'ACTIVE'
           AND appointment.effective_start_date <= current_date
           AND (appointment.effective_end_date IS NULL
                OR appointment.effective_end_date >= current_date)
           AND NOT appointment.is_deleted
         WHERE account.tenant_id = v_tenant_id
           AND account.id = v_user_id
           AND account.status = 'ACTIVE'
           AND account.mfa_level = 0
           AND NOT account.is_deleted
    ) THEN
        RAISE EXCEPTION 'bootstrap administrator login graph is incomplete';
    END IF;

    IF (
        SELECT count(DISTINCT permission.permission_code)
          FROM iam.user_role user_role
          JOIN iam.role role
            ON role.tenant_id = user_role.tenant_id
           AND role.id = user_role.role_id
           AND role.enabled
           AND NOT role.is_deleted
          JOIN iam.role_permission role_permission
            ON role_permission.tenant_id = role.tenant_id
           AND role_permission.role_id = role.id
           AND NOT role_permission.is_deleted
          JOIN iam.permission permission
            ON permission.tenant_id = role_permission.tenant_id
           AND permission.id = role_permission.permission_id
           AND NOT permission.is_deleted
         WHERE user_role.tenant_id = v_tenant_id
           AND user_role.user_id = v_user_id
           AND (user_role.identity_id IS NULL OR user_role.identity_id = v_identity_id)
           AND user_role.effective_start_at <= now()
           AND (user_role.effective_end_at IS NULL OR user_role.effective_end_at > now())
           AND NOT user_role.is_deleted
           AND permission.permission_code IN (
               'platform.session.read',
               'platform.session.switch',
               'platform.session.logout',
               'platform.stepup.issue'
           )
    ) <> 4 THEN
        RAISE EXCEPTION 'bootstrap administrator permissions are incomplete';
    END IF;
END
$$;

RESET ROLE;
