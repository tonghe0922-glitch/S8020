import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf-8')
}

describe('组织架构页面紧凑布局与大字版契约', () => {
  it('员工端与技术端均隐藏全局重复大标题', () => {
    const layout = source('../../AuthenticatedPortalLayout.vue')

    expect(layout).toContain("'org-directory-architecture'")
    expect(layout).toContain("'org-architecture-tech'")
    expect(layout).toContain(':show-page-title="showPageTitle"')
  })

  it('技术端折叠内部重复标题区域并保留紧凑刷新入口', () => {
    const page = source('./OrgArchitectureTechPage.vue')
    const styles = source('./org-page-typography.css')

    expect(page).toContain('org-console__header')
    expect(styles).toContain('.org-console > .org-console__header')
    expect(styles).toContain('display: contents !important')
    expect(styles).toContain('.org-console > .org-console__header .org-console__heading')
    expect(styles).toContain('.org-console > .org-console__header .is-secondary')
  })

  it('技术端与员工端组织详情文字统一增加两像素', () => {
    const styles = source('./org-page-typography.css')

    expect(styles).toContain('--org-detail-font-step: 2px')
    expect(styles).toContain('--directory-detail-font-step: 2px')
    expect(styles).toContain('.org-console .org-tree__content strong')
    expect(styles).toContain('.org-console .org-editor label')
    expect(styles).toContain('.directory-page.is-architecture .org-chart__title-line strong')
    expect(styles).toContain('.directory-page.is-architecture .directory-page__drawer-section > p')
  })
})
