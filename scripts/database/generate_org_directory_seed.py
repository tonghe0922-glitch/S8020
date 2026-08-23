#!/usr/bin/env python3
"""Generate the authorized S8020 real organization and directory Flyway seed."""

from __future__ import annotations

from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "technical-platform/database/seeds/org-directory-source.json"
MIGRATION = ROOT / "technical-platform/database/flyway-overlays/oms/V9004__org_directory_full_remediation.sql"
MANIFEST = ROOT / "technical-platform/database/seeds/org-directory-seed-manifest.json"

KIND_MAP = {
    "管理": "MANAGEMENT",
    "直管": "DIRECT",
    "公司": "COMPANY",
    "中心": "CENTER",
    "部门": "DEPARTMENT",
    "组": "GROUP",
}

PRIORITY_EMPLOYEES = [
    "黄水财",
    "伍伟佳",
    "施璐璐",
    "吴全球",
    "薛瑞岳",
    "王君英",
    "王刚",
    "张孝辉",
    "胡启红",
    "叶孝帅",
    "张金斌",
    "潘晓庭",
]

KNOWN_HIRE_DATES = {
    "黄水财": "2018-06-20",
    "伍伟佳": "2019-03-15",
    "施璐璐": "2019-04-01",
    "吴全球": "2018-08-01",
    "薛瑞岳": "2018-08-01",
    "王君英": "2018-08-01",
    "王刚": "2019-01-01",
    "张孝辉": "2019-01-01",
    "胡启红": "2019-01-01",
    "叶孝帅": "2019-01-01",
    "张金斌": "2018-06-20",
    "潘晓庭": "2018-09-01",
}


def sql(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def path_label(code: str) -> str:
    return code.lower().replace("-", "_")


def source_records(source: dict) -> tuple[list[dict], list[dict]]:
    organizations: list[dict] = []
    appointments: list[dict] = []

    def walk(node: dict, parent_code: str | None = None, parent_path: str | None = None, depth: int = 0) -> None:
        code = node["code"]
        path = f"{parent_path}.{path_label(code)}" if parent_path else path_label(code)
        members = node.get("members", [])
        manager_name = next(
            (
                member.get("n", "").strip()
                for member in members
                if member.get("n", "").strip() and member.get("t", "").strip()
            ),
            None,
        )
        organizations.append(
            {
                "code": code,
                "name": node["name"],
                "type": KIND_MAP[node["kind"]],
                "parentCode": parent_code,
                "path": path,
                "depth": depth,
                "sortNo": int(code.rsplit("-", 1)[-1]),
                "headcountPlan": int(node.get("plan") or 0),
                "description": (node.get("desc") or "").strip() or None,
                "managerName": manager_name,
            }
        )
        for member in members:
            name = member.get("n", "").strip()
            if name:
                appointments.append(
                    {
                        "orgCode": code,
                        "personName": name,
                        "positionName": member.get("t", "").strip() or "成员",
                    }
                )
        for child in node.get("children", []):
            walk(child, code, path, depth + 1)

    walk(source["tree"])
    return organizations, appointments


def build_model(source: dict) -> dict:
    organizations, raw_appointments = source_records(source)
    expected_codes = [f"S06-ORG-{index:03d}" for index in range(1, 60)]
    if [row["code"] for row in organizations] != expected_codes:
        raise ValueError("organization source must contain S06-ORG-001 through S06-ORG-059 in order")

    names: list[str] = []
    for row in raw_appointments:
        if row["personName"] not in names:
            names.append(row["personName"])
    for name in PRIORITY_EMPLOYEES:
        if name not in names:
            raise ValueError(f"required guide employee is missing: {name}")
    ordered_names = PRIORITY_EMPLOYEES + [name for name in names if name not in PRIORITY_EMPLOYEES]
    employee_numbers = {name: f"S06-E{index:03d}" for index, name in enumerate(ordered_names, 1)}

    positions: list[dict] = []
    position_codes: dict[tuple[str, str], str] = {}
    for row in raw_appointments:
        key = (row["orgCode"], row["positionName"])
        if key not in position_codes:
            code = f"S06-POS-{len(positions) + 1:03d}"
            position_codes[key] = code
            positions.append(
                {
                    "positionCode": code,
                    "orgCode": row["orgCode"],
                    "positionName": row["positionName"],
                }
            )

    primary_seen: set[str] = set()
    appointments: list[dict] = []
    for index, row in enumerate(raw_appointments, 1):
        name = row["personName"]
        primary = name not in primary_seen
        primary_seen.add(name)
        appointments.append(
            {
                "appointmentCode": f"S06-APPT-{index:03d}",
                "employeeNo": employee_numbers[name],
                "positionCode": position_codes[(row["orgCode"], row["positionName"])],
                "orgCode": row["orgCode"],
                "primary": primary,
            }
        )

    employees = [
        {
            "employeeNo": employee_numbers[name],
            "personName": name,
            "hireDate": KNOWN_HIRE_DATES.get(name),
        }
        for name in ordered_names
    ]
    for organization in organizations:
        manager = organization.pop("managerName")
        organization["managerEmployeeNo"] = employee_numbers.get(manager) if manager else None

    expected = {
        "organizations": source["expectedOrganizationCount"],
        "employees": source["expectedEmployeeCount"],
        "appointments": source["expectedAppointmentCount"],
    }
    actual = {
        "organizations": len(organizations),
        "employees": len(employees),
        "appointments": len(appointments),
    }
    if actual != expected:
        raise ValueError(f"source count mismatch: expected={expected}, actual={actual}")
    if sum(1 for row in appointments if row["primary"]) != len(employees):
        raise ValueError("every employee must have exactly one primary appointment")

    return {
        "organizations": organizations,
        "positions": positions,
        "employees": employees,
        "appointments": appointments,
    }


def values(lines: list[str], rows: list[str]) -> None:
    for index, row in enumerate(rows):
        lines.append(f"    {row}{',' if index < len(rows) - 1 else ';'}")


def generate_migration(source: dict, model: dict) -> str:
    organizations = model["organizations"]
    positions = model["positions"]
    employees = model["employees"]
    appointments = model["appointments"]
    import_date = source["authorizedImportDate"]
    lines: list[str] = [
        "-- V9004: complete organization directory remediation.",
        f"-- Authorized source: {source['sourceFile']}",
        f"-- Source SHA-256: {source['sourceSha256']}",
        "-- Invariants: 59 S06 organization nodes, 205 unique real employees, 217 active appointments.",
        "-- Unknown hire dates remain NULL; appointment effective date is the controlled import date.",
        "SET ROLE sjg_owner;",
        "",
        "CREATE TABLE IF NOT EXISTS org.organization_code_sequence (",
        "    tenant_id uuid NOT NULL PRIMARY KEY,",
        "    next_value integer NOT NULL,",
        "    updated_at timestamptz DEFAULT now() NOT NULL,",
        "    CONSTRAINT ck_org_organization_code_sequence_next CHECK (next_value > 0)",
        ");",
        "COMMENT ON TABLE org.organization_code_sequence IS '组织编号租户序列｜服务端原子分配 S06-ORG-xxx 编号';",
        "",
        "DO $$",
        "BEGIN",
        "    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_org_organization_code_sequence_tenant') THEN",
        "        ALTER TABLE org.organization_code_sequence",
        "            ADD CONSTRAINT fk_org_organization_code_sequence_tenant",
        "            FOREIGN KEY (tenant_id) REFERENCES core.tenant(id) ON DELETE RESTRICT NOT VALID;",
        "        ALTER TABLE org.organization_code_sequence",
        "            VALIDATE CONSTRAINT fk_org_organization_code_sequence_tenant;",
        "    END IF;",
        "END",
        "$$;",
        "",
        "ALTER TABLE org.organization_code_sequence ENABLE ROW LEVEL SECURITY;",
        "DROP POLICY IF EXISTS p_tenant_org_organization_code_sequence ON org.organization_code_sequence;",
        "CREATE POLICY p_tenant_org_organization_code_sequence ON org.organization_code_sequence",
        "    USING (tenant_id=current_setting('app.tenant_id',true)::uuid)",
        "    WITH CHECK (tenant_id=current_setting('app.tenant_id',true)::uuid);",
        "GRANT SELECT,INSERT,UPDATE ON org.organization_code_sequence TO sjg_api_runtime;",
        "",
        "CREATE TEMP TABLE seed_v9004_org (",
        "    org_code text PRIMARY KEY, org_name text NOT NULL, org_type text NOT NULL, parent_code text,",
        "    path text NOT NULL, depth integer NOT NULL, sort_no integer NOT NULL, headcount_plan integer NOT NULL,",
        "    description text, manager_employee_no text",
        ") ON COMMIT DROP;",
        "INSERT INTO seed_v9004_org VALUES",
    ]
    values(
        lines,
        [
            "(" + ",".join(
                [
                    sql(row["code"]),
                    sql(row["name"]),
                    sql(row["type"]),
                    sql(row["parentCode"]),
                    sql(row["path"]),
                    str(row["depth"]),
                    str(row["sortNo"]),
                    str(row["headcountPlan"]),
                    sql(row["description"]),
                    sql(row["managerEmployeeNo"]),
                ]
            ) + ")"
            for row in organizations
        ],
    )
    lines.extend(
        [
            "",
            "CREATE TEMP TABLE seed_v9004_position (",
            "    position_code text PRIMARY KEY, org_code text NOT NULL, position_name text NOT NULL",
            ") ON COMMIT DROP;",
            "INSERT INTO seed_v9004_position VALUES",
        ]
    )
    values(
        lines,
        [
            f"({sql(row['positionCode'])},{sql(row['orgCode'])},{sql(row['positionName'])})"
            for row in positions
        ],
    )
    lines.extend(
        [
            "",
            "CREATE TEMP TABLE seed_v9004_employee (",
            "    employee_no text PRIMARY KEY, person_name text NOT NULL, hire_date date",
            ") ON COMMIT DROP;",
            "INSERT INTO seed_v9004_employee VALUES",
        ]
    )
    values(
        lines,
        [
            f"({sql(row['employeeNo'])},{sql(row['personName'])},"
            + (f"{sql(row['hireDate'])}::date" if row["hireDate"] else "NULL")
            + ")"
            for row in employees
        ],
    )
    lines.extend(
        [
            "",
            "CREATE TEMP TABLE seed_v9004_appointment (",
            "    appointment_code text PRIMARY KEY, employee_no text NOT NULL, position_code text NOT NULL,",
            "    org_code text NOT NULL, is_primary boolean NOT NULL",
            ") ON COMMIT DROP;",
            "INSERT INTO seed_v9004_appointment VALUES",
        ]
    )
    values(
        lines,
        [
            f"({sql(row['appointmentCode'])},{sql(row['employeeNo'])},{sql(row['positionCode'])},"
            f"{sql(row['orgCode'])},{str(row['primary']).lower()})"
            for row in appointments
        ],
    )
    lines.extend(
        f'''\nDO $v9004$\nDECLARE\n    v_tenant_id uuid := '${{sjg_tenant_id}}'::uuid;\n    v_admin_employee_no text := nullif(btrim(current_setting('sjg.bootstrap.admin_employee_no', true)), '');\n    v_actor_employee_id uuid;\n    v_root_org_id uuid;\n    v_admin_position_id uuid;\n    v_row record;\n    v_parent_id uuid;\n    v_org_id uuid;\n    v_position_id uuid;\n    v_employee_id uuid;\nBEGIN\n    PERFORM set_config('app.tenant_id', v_tenant_id::text, true);\n\n    IF v_admin_employee_no IS NULL THEN\n        IF session_user IN ('postgres','sjg_bootstrap') THEN\n            RAISE NOTICE 'V9004 real organization directory seed skipped for direct superuser test migration';\n            RETURN;\n        END IF;\n        RAISE EXCEPTION 'V9004 requires the controlled bootstrap administrator settings';\n    END IF;\n\n    SELECT id INTO v_actor_employee_id\n      FROM org.employee\n     WHERE tenant_id=v_tenant_id AND employee_no=v_admin_employee_no AND NOT is_deleted\n     ORDER BY created_at,id LIMIT 1;\n    IF v_actor_employee_id IS NULL THEN\n        RAISE EXCEPTION 'V9004 could not resolve the controlled bootstrap administrator employee';\n    END IF;\n\n    FOR v_row IN SELECT * FROM seed_v9004_org ORDER BY depth,sort_no,org_code LOOP\n        IF v_row.parent_code IS NULL THEN\n            v_parent_id := NULL;\n        ELSE\n            SELECT id INTO v_parent_id FROM org.organization\n             WHERE tenant_id=v_tenant_id AND org_code=v_row.parent_code AND NOT is_deleted LIMIT 1;\n            IF v_parent_id IS NULL THEN\n                RAISE EXCEPTION 'V9004 parent organization missing: %', v_row.parent_code;\n            END IF;\n        END IF;\n\n        INSERT INTO org.organization(\n            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,\n            org_code,org_name,org_type,parent_id,path,manager_employee_id,status,\n            description,headcount_plan,sort_no,version_no)\n        VALUES (\n            md5(v_tenant_id::text || ':v9004:org:' || v_row.org_code)::uuid,\n            v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),false,NULL,\n            v_row.org_code,v_row.org_name,v_row.org_type,v_parent_id,v_row.path::ltree,NULL,'ACTIVE',\n            v_row.description,v_row.headcount_plan,v_row.sort_no,1)\n        ON CONFLICT (tenant_id,org_code) DO UPDATE SET\n            org_name=EXCLUDED.org_name,org_type=EXCLUDED.org_type,parent_id=EXCLUDED.parent_id,\n            path=EXCLUDED.path,status='ACTIVE',description=EXCLUDED.description,\n            headcount_plan=EXCLUDED.headcount_plan,sort_no=EXCLUDED.sort_no,\n            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;\n    END LOOP;\n\n    FOR v_row IN SELECT * FROM seed_v9004_position ORDER BY position_code LOOP\n        SELECT id INTO v_org_id FROM org.organization\n         WHERE tenant_id=v_tenant_id AND org_code=v_row.org_code AND NOT is_deleted LIMIT 1;\n        INSERT INTO org.position(\n            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,\n            position_code,position_name,org_id,status)\n        VALUES (md5(v_tenant_id::text || ':v9004:position:' || v_row.position_code)::uuid,\n                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),\n                false,NULL,v_row.position_code,v_row.position_name,v_org_id,'ACTIVE')\n        ON CONFLICT (tenant_id,position_code) DO UPDATE SET\n            position_name=EXCLUDED.position_name,org_id=EXCLUDED.org_id,status='ACTIVE',\n            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;\n    END LOOP;\n\n    FOR v_row IN SELECT * FROM seed_v9004_employee ORDER BY employee_no LOOP\n        INSERT INTO org.employee AS employee(\n            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,\n            employee_no,person_name,employment_status,hire_date,leave_date)\n        VALUES (md5(v_tenant_id::text || ':v9004:employee:' || v_row.employee_no)::uuid,\n                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),\n                false,NULL,v_row.employee_no,v_row.person_name,'ACTIVE',v_row.hire_date,NULL)\n        ON CONFLICT (tenant_id,employee_no) DO UPDATE SET\n            person_name=EXCLUDED.person_name,employment_status='ACTIVE',\n            hire_date=COALESCE(employee.hire_date,EXCLUDED.hire_date),leave_date=NULL,\n            updated_by=v_actor_employee_id,updated_at=now(),is_deleted=false,deleted_at=NULL;\n    END LOOP;\n\n    FOR v_row IN SELECT * FROM seed_v9004_appointment ORDER BY appointment_code LOOP\n        SELECT id INTO v_employee_id FROM org.employee\n         WHERE tenant_id=v_tenant_id AND employee_no=v_row.employee_no AND NOT is_deleted LIMIT 1;\n        SELECT id INTO v_position_id FROM org.position\n         WHERE tenant_id=v_tenant_id AND position_code=v_row.position_code AND NOT is_deleted LIMIT 1;\n        SELECT id INTO v_org_id FROM org.organization\n         WHERE tenant_id=v_tenant_id AND org_code=v_row.org_code AND NOT is_deleted LIMIT 1;\n\n        INSERT INTO org.employee_position(\n            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,deleted_at,\n            employee_id,position_id,org_id,is_primary,effective_start_date,effective_end_date,status)\n        VALUES (md5(v_tenant_id::text || ':v9004:appointment:' || v_row.appointment_code)::uuid,\n                v_tenant_id,v_actor_employee_id,now(),v_actor_employee_id,now(),\n                false,NULL,v_employee_id,v_position_id,v_org_id,v_row.is_primary,DATE '{import_date}',NULL,'ACTIVE')\n        ON CONFLICT (id) DO UPDATE SET\n            employee_id=EXCLUDED.employee_id,position_id=EXCLUDED.position_id,org_id=EXCLUDED.org_id,\n            is_primary=EXCLUDED.is_primary,effective_start_date=EXCLUDED.effective_start_date,\n            effective_end_date=NULL,status='ACTIVE',updated_by=v_actor_employee_id,updated_at=now(),\n            is_deleted=false,deleted_at=NULL;\n    END LOOP;\n\n    UPDATE org.employee employee\n       SET primary_org_id=organization.id,primary_position_id=position.id,\n           updated_by=v_actor_employee_id,updated_at=now()\n      FROM seed_v9004_appointment seed\n      JOIN org.organization organization\n        ON organization.tenant_id=v_tenant_id AND organization.org_code=seed.org_code AND NOT organization.is_deleted\n      JOIN org.position position\n        ON position.tenant_id=v_tenant_id AND position.position_code=seed.position_code AND NOT position.is_deleted\n     WHERE seed.is_primary\n       AND employee.tenant_id=v_tenant_id AND employee.employee_no=seed.employee_no AND NOT employee.is_deleted;\n\n    UPDATE org.organization organization\n       SET manager_employee_id=employee.id,updated_by=v_actor_employee_id,updated_at=now()\n      FROM seed_v9004_org seed\n      JOIN org.employee employee\n        ON employee.tenant_id=v_tenant_id AND employee.employee_no=seed.manager_employee_no AND NOT employee.is_deleted\n     WHERE organization.tenant_id=v_tenant_id AND organization.org_code=seed.org_code\n       AND seed.manager_employee_no IS NOT NULL AND NOT organization.is_deleted;\n\n    SELECT id INTO v_root_org_id FROM org.organization\n     WHERE tenant_id=v_tenant_id AND org_code='S06-ORG-001' AND NOT is_deleted;\n    SELECT id INTO v_admin_position_id FROM org.position\n     WHERE tenant_id=v_tenant_id AND position_code='S8020-ADMIN' AND NOT is_deleted;\n    UPDATE org.position SET org_id=v_root_org_id,updated_by=v_actor_employee_id,updated_at=now()\n     WHERE tenant_id=v_tenant_id AND position_code='S8020-ADMIN' AND NOT is_deleted;\n    UPDATE org.employee SET primary_org_id=v_root_org_id,primary_position_id=v_admin_position_id,\n           updated_by=v_actor_employee_id,updated_at=now()\n     WHERE tenant_id=v_tenant_id AND id=v_actor_employee_id;\n    UPDATE org.employee_position SET org_id=v_root_org_id,position_id=v_admin_position_id,\n           updated_by=v_actor_employee_id,updated_at=now()\n     WHERE tenant_id=v_tenant_id AND employee_id=v_actor_employee_id AND status='ACTIVE' AND NOT is_deleted;\n    UPDATE iam.user_identity SET org_id=v_root_org_id,position_id=v_admin_position_id,updated_at=now()\n     WHERE tenant_id=v_tenant_id AND employee_id=v_actor_employee_id AND NOT is_deleted;\n    UPDATE org.organization SET status='INACTIVE',is_deleted=true,deleted_at=now(),\n           updated_by=v_actor_employee_id,updated_at=now()\n     WHERE tenant_id=v_tenant_id AND org_code='S8020-PLATFORM' AND NOT is_deleted;\n\n    UPDATE iam.role_permission role_permission\n       SET is_deleted=false,updated_at=now()\n      FROM iam.role role,iam.permission permission\n     WHERE role_permission.tenant_id=v_tenant_id\n       AND role_permission.role_id=role.id AND role_permission.permission_id=permission.id\n       AND role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'\n       AND permission.tenant_id=v_tenant_id AND role.enabled AND NOT role.is_deleted AND NOT permission.is_deleted;\n\n    INSERT INTO iam.role_permission(id,tenant_id,role_id,permission_id,created_at,updated_at,is_deleted)\n    SELECT gen_random_uuid(),v_tenant_id,role.id,permission.id,now(),now(),false\n      FROM iam.role role\n      CROSS JOIN iam.permission permission\n     WHERE role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'\n       AND permission.tenant_id=v_tenant_id AND role.enabled AND NOT role.is_deleted AND NOT permission.is_deleted\n       AND NOT EXISTS (SELECT 1 FROM iam.role_permission existing\n                        WHERE existing.tenant_id=v_tenant_id AND existing.role_id=role.id\n                          AND existing.permission_id=permission.id AND NOT existing.is_deleted);\n\n    INSERT INTO org.architecture_version(\n        tenant_id,version_no,source_draft_id,snapshot,change_summary,published_by,published_at)\n    SELECT v_tenant_id,\n           COALESCE((SELECT max(version_no) FROM org.architecture_version WHERE tenant_id=v_tenant_id),0)+1,\n           NULL,\n           COALESCE(jsonb_agg(jsonb_build_object(\n               'id',organization.id,'orgCode',organization.org_code,'orgName',organization.org_name,\n               'orgType',organization.org_type,'parentId',organization.parent_id,'path',organization.path::text,\n               'status',organization.status,'managerEmployeeId',organization.manager_employee_id,\n               'memberCount',(SELECT count(*) FROM org.employee_position appointment\n                    WHERE appointment.tenant_id=organization.tenant_id AND appointment.org_id=organization.id\n                      AND appointment.status='ACTIVE' AND appointment.effective_start_date<=current_date\n                      AND (appointment.effective_end_date IS NULL OR appointment.effective_end_date>=current_date)\n                      AND NOT appointment.is_deleted),\n               'headcountPlan',organization.headcount_plan,'sortNo',organization.sort_no,\n               'versionNo',organization.version_no,'description',organization.description\n           ) ORDER BY organization.path,organization.sort_no,organization.org_code),'[]'::jsonb),\n           '[{{"kind":"REAL_DIRECTORY_SEED_V9004","summary":"导入59个真实组织节点、205位员工和217条任职，并为超级管理员授予全量权限"}}]'::jsonb,\n           NULL,now()\n      FROM org.organization organization\n     WHERE organization.tenant_id=v_tenant_id AND organization.org_code~'^S06-ORG-[0-9]{{3}}$'\n       AND NOT organization.is_deleted\n       AND NOT EXISTS (\n           SELECT 1 FROM org.architecture_version version\n            WHERE version.tenant_id=v_tenant_id\n              AND version.change_summary @> '[{{"kind":"REAL_DIRECTORY_SEED_V9004"}}]'::jsonb);\n\n    IF (SELECT count(*) FROM seed_v9004_org seed\n         JOIN org.organization organization ON organization.tenant_id=v_tenant_id\n          AND organization.org_code=seed.org_code AND NOT organization.is_deleted) <> 59 THEN\n        RAISE EXCEPTION 'V9004 organization count assertion failed';\n    END IF;\n    IF (SELECT count(*) FROM seed_v9004_employee seed\n         JOIN org.employee employee ON employee.tenant_id=v_tenant_id\n          AND employee.employee_no=seed.employee_no AND NOT employee.is_deleted) <> 205 THEN\n        RAISE EXCEPTION 'V9004 employee count assertion failed';\n    END IF;\n    IF (SELECT count(*) FROM seed_v9004_appointment seed\n         JOIN org.employee_position appointment\n           ON appointment.id=md5(v_tenant_id::text || ':v9004:appointment:' || seed.appointment_code)::uuid\n          AND appointment.tenant_id=v_tenant_id AND appointment.status='ACTIVE' AND NOT appointment.is_deleted) <> 217 THEN\n        RAISE EXCEPTION 'V9004 appointment count assertion failed';\n    END IF;\n    IF (SELECT count(DISTINCT role_permission.permission_id)\n          FROM iam.role role\n          JOIN iam.role_permission role_permission ON role_permission.tenant_id=role.tenant_id\n               AND role_permission.role_id=role.id AND NOT role_permission.is_deleted\n          JOIN iam.permission permission ON permission.tenant_id=role_permission.tenant_id\n               AND permission.id=role_permission.permission_id AND NOT permission.is_deleted\n         WHERE role.tenant_id=v_tenant_id AND role.role_code='S8020_BOOTSTRAP_ADMIN'\n           AND role.enabled AND NOT role.is_deleted)\n       <> (SELECT count(*) FROM iam.permission WHERE tenant_id=v_tenant_id AND NOT is_deleted) THEN\n        RAISE EXCEPTION 'V9004 bootstrap administrator permission assertion failed';\n    END IF;\nEND\n$v9004$;\n\nINSERT INTO org.organization_code_sequence(tenant_id,next_value,updated_at)\nSELECT '${{sjg_tenant_id}}'::uuid,\n       COALESCE(max(cast(substring(org_code from 'S06-ORG-([0-9]+)') as integer)),0)+1,\n       now()\n  FROM org.organization\n WHERE tenant_id='${{sjg_tenant_id}}'::uuid AND org_code~'^S06-ORG-[0-9]+$' AND NOT is_deleted\nON CONFLICT (tenant_id) DO UPDATE SET\n    next_value=GREATEST(org.organization_code_sequence.next_value,EXCLUDED.next_value),\n    updated_at=now();\n\nRESET ROLE;\n'''.splitlines()
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    source = json.loads(SOURCE.read_text(encoding="utf-8"))
    model = build_model(source)
    MIGRATION.write_text(generate_migration(source, model), encoding="utf-8")
    manifest = {
        "source": source["sourceFile"],
        "sourceSha256": source["sourceSha256"],
        "authorizedImportDate": source["authorizedImportDate"],
        "organizationCount": len(model["organizations"]),
        "positionCount": len(model["positions"]),
        "employeeCount": len(model["employees"]),
        "appointmentCount": len(model["appointments"]),
        "organizationCodes": [row["code"] for row in model["organizations"]],
        "employeeNumbers": [row["employeeNo"] for row in model["employees"]],
        "appointmentCodes": [row["appointmentCode"] for row in model["appointments"]],
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "Generated V9004 organization directory seed: "
        f"org={manifest['organizationCount']} employees={manifest['employeeCount']} "
        f"appointments={manifest['appointmentCount']} positions={manifest['positionCount']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
