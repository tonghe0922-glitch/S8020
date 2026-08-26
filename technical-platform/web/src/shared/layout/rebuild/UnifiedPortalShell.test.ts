// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import { PORTALS } from '../../../platform/portal-config'
import UnifiedPortalShell from './UnifiedPortalShell.vue'

const StubPage = defineComponent({ template: '<div />' })

async function mountedShell(searchItems: readonly {
  sourceKey: string
  label: string
  routePath: string
}[] = [], showPageTitle = true) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: StubPage },
      { path: '/tasks', component: StubPage },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(UnifiedPortalShell, {
    props: {
      portal: PORTALS.work,
      pageTitle: '工作台',
      showPageTitle,
      searchItems,
    },
    slots: {
      sidebar: '<div data-test="sidebar-slot">侧边导航</div>',
      bottomNav: '<div data-test="bottom-slot">移动导航</div>',
      default: '<div data-test="page-content">页面内容</div>',
    },
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

function routePath(router: Router): string {
  return router.currentRoute.value.path
}

describe('rebuild unified portal shell', () => {
  it('keeps route content, navigation slots and the two-port brand context visible', async () => {
    const { wrapper } = await mountedShell()

    expect(wrapper.text()).toContain('上金谷管理平台')
    expect(wrapper.text()).toContain('数字化现场调度协同系统')
    expect(wrapper.get('.rebuild-shell__brand-tag').text()).toBe('工作端')
    expect(wrapper.get('.rebuild-shell__brand-logo').attributes('alt')).toBe('上金谷品牌标志')
    expect(wrapper.get('.rebuild-shell__brand-logo').attributes('src')).toContain('LOGO.svg')
    expect(wrapper.get('[data-test="sidebar-slot"]').text()).toBe('侧边导航')
    expect(wrapper.get('[data-test="bottom-slot"]').text()).toBe('移动导航')
    expect(wrapper.get('[data-test="page-content"]').text()).toBe('页面内容')
  })

  it('searches only supplied accessible routes and navigates through Vue Router', async () => {
    const { wrapper, router } = await mountedShell([
      { sourceKey: 'tasks', label: '待办与任务', routePath: '/tasks' },
    ])

    const input = wrapper.get('[data-test="shell-search"]')
    await input.trigger('focus')
    await input.setValue('待办')
    await flushPromises()

    const result = wrapper.findAll('.rebuild-shell__search-results button')
      .find((button) => button.text().includes('待办与任务'))
    expect(result).toBeDefined()

    await result?.trigger('click')
    await flushPromises()
    expect(routePath(router)).toBe('/tasks')
  })

  it('keeps breadcrumbs while suppressing a duplicated visual page title', async () => {
    const { wrapper } = await mountedShell([], false)

    expect(wrapper.get('.rebuild-shell__breadcrumbs').text()).toContain('工作台')
    expect(wrapper.find('.rebuild-shell__page-title').exists()).toBe(false)
    expect(wrapper.get('[data-test="page-content"]').text()).toBe('页面内容')
  })

})
