// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import {
  projectNavigationGroups,
  projectedItemIsActive,
  resolveActiveGroup,
  resolveActiveItem,
  type NavigationProjectionOptions,
} from './navigation-projection'
import PortalNavigation from './PortalNavigation.vue'

const WORK_ORDER = [
  '我的工作台',
  '中心事务',
  '企业通讯录',
  '待办与任务',
  '审批与申请',
  '通知与制度',
  '例会与会议',
  '学习与成行',
  '演出节目单',
  '报销与经费',
  '个人综合服务',
  '站内通信',
]

function projectionOptions(overrides: Partial<NavigationProjectionOptions> = {}): NavigationProjectionOptions {
  return {
    runtimeCode: 'work',
    permissions: new Set(),
    implementedRoutePaths: new Set(['/']),
    routeAccessRules: new Map(),
    mobile: false,
    ...overrides,
  }
}

describe('canonical two-port navigation projection', () => {
  it('keeps the approved work navigation order', () => {
    const groups = projectNavigationGroups(projectionOptions())
    expect(groups.map((group) => group.label)).toEqual(WORK_ORDER)
  })

  it('keeps the tech navigation independent from the work navigation', () => {
    const groups = projectNavigationGroups(projectionOptions({ runtimeCode: 'tech' }))
    expect(groups.map((group) => group.label)).toEqual([
      '技术工作台',
      '权限与模块配置',
      '身份与安全',
      '流程与运行',
      '人员业务监控',
    ])
    expect(groups.map((group) => group.label)).not.toContain('企业通讯录')
  })

  it('marks an implemented route unauthorized when router metadata denies it', () => {
    const routeAccessRules = new Map([['/employee/03/07/04', { any: ['p002.request.read'] }]])
    const groups = projectNavigationGroups(projectionOptions({
      implementedRoutePaths: new Set(['/', '/employee/03/07/04']),
      routeAccessRules,
    }))
    const item = groups.find((group) => group.key === 'approvals')?.items
      .find((candidate) => candidate.key === 'approvals-created')
    expect(item?.state).toBe('unauthorized')
    expect(item?.routePath).toContain('/forbidden?')
  })

  it('resolves exactly one placeholder item by its module key', () => {
    const groups = projectNavigationGroups(projectionOptions())
    const active = resolveActiveItem(groups, '/developing', 'tasks-created')
    expect(active?.key).toBe('tasks-created')
    expect(resolveActiveGroup(groups, '/developing', 'tasks-created')?.key).toBe('tasks')
    const matches = groups.flatMap((group) => group.items)
      .filter((item) => projectedItemIsActive('/developing', 'tasks-created', item))
    expect(matches).toHaveLength(1)
  })
})

describe('PortalNavigation interaction', () => {
  function createTestRouter() {
    return createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/employee/03/07/04', component: { template: '<div />' } },
        { path: '/developing', component: { template: '<div />' } },
        { path: '/forbidden', component: { template: '<div />' } },
      ],
    })
  }

  it('renders all work groups in the approved order', async () => {
    setActivePinia(createPinia())
    const router = createTestRouter()
    await router.push('/')
    const wrapper = mount(PortalNavigation, {
      props: { runtimeCode: 'work' },
      global: { plugins: [router] },
    })
    const labels = wrapper.findAll('.portal-navigation__group')
      .map((group) => group.find('.portal-navigation__top-link, .portal-navigation__group-button').text())
    expect(labels.map((label) => WORK_ORDER.find((item) => label.includes(item)))).toEqual(WORK_ORDER)
  })

  it('never marks multiple placeholder children active', async () => {
    setActivePinia(createPinia())
    const router = createTestRouter()
    await router.push('/')
    const wrapper = mount(PortalNavigation, {
      props: { runtimeCode: 'work' },
      global: { plugins: [router] },
      attachTo: document.body,
    })
    const taskButton = wrapper.findAll('.portal-navigation__group-button')
      .find((button) => button.text().includes('待办与任务'))
    await taskButton?.trigger('click')
    const myTasks = wrapper.findAll('.portal-navigation__child-link')
      .find((link) => link.text().includes('我的待办'))
    await myTasks?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.module).toBe('tasks-mine')
    expect(wrapper.findAll('.portal-navigation__child-link.is-active')).toHaveLength(1)
    wrapper.unmount()
  })

  it('selecting the directory does not turn unrelated subcategories orange', async () => {
    setActivePinia(createPinia())
    const router = createTestRouter()
    await router.push('/')
    const wrapper = mount(PortalNavigation, {
      props: { runtimeCode: 'work' },
      global: { plugins: [router] },
    })
    const directory = wrapper.findAll('.portal-navigation__top-link')
      .find((link) => link.text().includes('企业通讯录'))
    await directory?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.module).toBe('contacts')
    expect(wrapper.findAll('.portal-navigation__top-link.is-active')).toHaveLength(1)
    expect(wrapper.findAll('.portal-navigation__child-link.is-active')).toHaveLength(0)
  })
})
