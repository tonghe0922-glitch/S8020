import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function source(relativePath: string): string {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('organization directory remediation UI', () => {
  it('keeps organization codes server-managed and read-only', () => {
    const editor = source('./OrgNodeEditor.vue')
    expect(editor).toContain('组织编码（系统自动生成）')
    expect(editor).toContain('readonly')
    expect(editor).toContain('disabled')
    expect(editor).not.toContain("@input=\"update('orgCode'")
  })

  it('uses company-name terminology on the login page', () => {
    const login = source('../LoginPage.vue')
    expect(login).toContain('公司名称（或租户编码）')
    expect(login).toContain('请输入公司名称或租户编码')
    expect(login).not.toContain('label="租户编码"')
  })

  it('uses compact headers without the obsolete hero and version cards', () => {
    const tech = source('./OrgArchitectureTechPage.vue')
    const directory = source('./OrgDirectoryPage.vue')
    expect(tech).toContain('org-page__header-compact')
    expect(tech).not.toContain('org-page__hero')
    expect(directory).toContain('directory-page__header-compact')
    expect(directory).not.toContain('正式版本 V')
  })
})
