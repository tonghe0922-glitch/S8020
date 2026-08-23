import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

interface SeedManifest {
  organizationCount: number
  positionCount: number
  employeeCount: number
  appointmentCount: number
  organizationCodes: string[]
  employeeNumbers: string[]
  appointmentCodes: string[]
}

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf-8')
}

function sequence(prefix: string, start: number, end: number): string[] {
  return Array.from({ length: end - start + 1 }, (_, offset) => `${prefix}${String(start + offset).padStart(3, '0')}`)
}

describe('组织架构与中文体验整改契约', () => {
  it('组织编号只读且不能由前端提交修改', () => {
    const editor = source('./OrgNodeEditor.vue')
    expect(editor).toContain('组织编码（系统自动生成）')
    expect(editor).toContain('readonly')
    expect(editor).not.toContain("update('orgCode'")
  })

  it('登录页使用公司名称表达', () => {
    const login = source('../LoginPage.vue')
    expect(login).toContain('公司名称（或租户编码）')
    expect(login).toContain('请输入公司名称或租户编码')
    expect(login).not.toContain('label="租户编码"')
  })

  it('组织与通讯录页面保持紧凑布局', () => {
    const tech = source('./OrgArchitectureTechPage.vue')
    const directory = source('./OrgDirectoryPage.vue')
    expect(tech).toContain('org-page__header-compact')
    expect(tech).not.toContain('org-page__hero')
    expect(directory).toContain('directory-page__header-compact')
    expect(directory).not.toContain('正式版本 V')
  })

  it('全局错误映射提供中文兜底提示', () => {
    const apiError = source('../../../api/api-error.ts')
    expect(apiError).toContain('请求未成功')
    expect(apiError).toContain('请联系管理员')
  })

  it('正式数据清单固定为 59 个组织、205 位员工和 217 条任职', () => {
    const manifest = JSON.parse(
      source('../../../../../database/seeds/org-directory-seed-manifest.json'),
    ) as SeedManifest

    expect(manifest.organizationCount).toBe(59)
    expect(manifest.positionCount).toBeGreaterThan(0)
    expect(manifest.employeeCount).toBe(205)
    expect(manifest.appointmentCount).toBe(217)
    expect(manifest.organizationCodes).toEqual(sequence('S06-ORG-', 1, 59))
    expect(manifest.employeeNumbers).toEqual(sequence('S06-E', 1, 205))
    expect(manifest.appointmentCodes).toEqual(sequence('S06-APPT-', 1, 217))
  })

  it('V9004 同时包含真实数据、编号序列、全量权限和运行时断言', () => {
    const migration = source(
      '../../../../../database/flyway-overlays/oms/V9004__org_directory_full_remediation.sql',
    )

    expect([...new Set(migration.match(/S06-ORG-\d{3}/g) ?? [])].sort()).toEqual(sequence('S06-ORG-', 1, 59))
    expect([...new Set(migration.match(/S06-E\d{3}/g) ?? [])].sort()).toEqual(sequence('S06-E', 1, 205))
    expect([...new Set(migration.match(/S06-APPT-\d{3}/g) ?? [])].sort()).toEqual(sequence('S06-APPT-', 1, 217))
    expect(migration).toContain('S8020_BOOTSTRAP_ADMIN')
    expect(migration).toContain('iam.role_permission')
    expect(migration).toContain('org.organization_code_sequence')
    expect(migration).toContain('V9004 organization count assertion failed')
    expect(migration).toContain('V9004 employee count assertion failed')
    expect(migration).toContain('V9004 appointment count assertion failed')
    expect(migration).toContain('V9004 bootstrap administrator permission assertion failed')
  })
})
