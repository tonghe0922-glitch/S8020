import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf-8')
}

describe('组织架构详情页面原型复刻契约', () => {
  it('技术端保持左侧目录与右侧详情工作台版式', () => {
    const page = source('./OrgArchitectureTechPage.vue')
    expect(page).toContain('org-console__workspace')
    expect(page).toContain('org-console__directory')
    expect(page).toContain('org-console__detail')
    expect(page).toContain('组织成员')
    expect(page).toContain('模块与授权')
    expect(page).toContain('api.directory()')
  })

  it('员工端保持全宽组织图与详情抽屉版式', () => {
    const page = source('./OrgDirectoryPage.vue')
    expect(page).toContain('OrgArchitectureChart')
    expect(page).toContain('directory-page__architecture-card')
    expect(page).toContain('directory-page__drawer')
    expect(page).toContain('组织职责')
    expect(page).toContain('直属成员')
  })

  it('管理端复用组织图并保留草稿审批发布闭环', () => {
    const page = source('./OrgArchitectureManagePage.vue')
    expect(page).toContain('OrgArchitectureChart')
    expect(page).toContain('manage-page__architecture-card')
    expect(page).toContain('manage-page__drawer')
    expect(page).toContain('saveDraft')
    expect(page).toContain('submitDraft')
    expect(page).toContain('publishDraft')
  })

  it('组织图组件提供层级连线、折叠与详情选择', () => {
    const chart = source('./OrgArchitectureChart.vue')
    expect(chart).toContain('org-chart__children')
    expect(chart).toContain('org-chart__collapse')
    expect(chart).toContain("emit('select', node.id)")
  })
})
