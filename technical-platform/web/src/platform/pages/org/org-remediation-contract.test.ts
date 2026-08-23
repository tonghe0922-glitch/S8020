import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf-8')
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
    expect(apiError).toContain('操作未成功')
    expect(apiError).toContain('请联系管理员')
  })
})
