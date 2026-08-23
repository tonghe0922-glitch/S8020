#!/usr/bin/env python3
"""验证真实组织架构、通讯录和超级管理员授权迁移。"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "technical-platform/database/seeds/org-directory-seed-manifest.json"
MIGRATION = ROOT / "technical-platform/database/flyway-overlays/oms/V9004__org_directory_full_remediation.sql"


def fail(message: str) -> None:
    raise SystemExit(f"真实组织通讯录迁移校验失败：{message}")


if not MANIFEST.is_file():
    fail(f"缺少数据清单 {MANIFEST.relative_to(ROOT)}")
if not MIGRATION.is_file():
    fail(f"缺少正式 Flyway 迁移 {MIGRATION.relative_to(ROOT)}")

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
expected_codes = [f"S06-ORG-{index:03d}" for index in range(1, 60)]
expected_employees = [f"S06-E{index:03d}" for index in range(1, 206)]
expected_appointments = [f"S06-APPT-{index:03d}" for index in range(1, 218)]
expected = {
    "organizationCount": 59,
    "employeeCount": 205,
    "appointmentCount": 217,
}
for key, value in expected.items():
    if manifest.get(key) != value:
        fail(f"{key} 应为 {value}，实际为 {manifest.get(key)!r}")
if manifest.get("positionCount", 0) <= 0:
    fail("岗位数量必须大于零")
if manifest.get("organizationCodes") != expected_codes:
    fail("组织编码必须连续覆盖 S06-ORG-001 至 S06-ORG-059")
if manifest.get("employeeNumbers") != expected_employees:
    fail("员工编号必须连续覆盖 S06-E001 至 S06-E205")
if manifest.get("appointmentCodes") != expected_appointments:
    fail("任职编号必须连续覆盖 S06-APPT-001 至 S06-APPT-217")

migration = MIGRATION.read_text(encoding="utf-8")
if sorted(set(re.findall(r"S06-ORG-\d{3}", migration))) != expected_codes:
    fail("正式迁移未完整包含 59 个组织编码")
if sorted(set(re.findall(r"S06-E\d{3}", migration))) != expected_employees:
    fail("正式迁移未完整包含 205 个员工编号")
if sorted(set(re.findall(r"S06-APPT-\d{3}", migration))) != expected_appointments:
    fail("正式迁移未完整包含 217 个任职编号")
for required in (
    "S8020_BOOTSTRAP_ADMIN",
    "iam.role_permission",
    "iam.permission",
    "org.organization",
    "org.employee",
    "org.position",
    "org.employee_position",
    "org.architecture_version",
    "organization_code_sequence",
    "ON CONFLICT",
):
    if required.lower() not in migration.lower():
        fail(f"正式迁移缺少关键内容 {required}")

print(
    "真实组织通讯录迁移校验通过："
    f"59 个组织、205 位员工、217 条任职、{manifest['positionCount']} 个岗位"
)
