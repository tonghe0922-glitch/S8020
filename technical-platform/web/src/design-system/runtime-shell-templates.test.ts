import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import PortalShell from './layout/PortalShell.vue'
import ApprovalPageTemplate from './templates/ApprovalPageTemplate.vue'
import DashboardPageTemplate from './templates/DashboardPageTemplate.vue'
import DetailPageTemplate from './templates/DetailPageTemplate.vue'
import FormPageTemplate from './templates/FormPageTemplate.vue'
import ListPageTemplate from './templates/ListPageTemplate.vue'
import TimelinePageTemplate from './templates/TimelinePageTemplate.vue'
import {
  findByClass,
  findByTag,
  mountRuntime,
} from './runtimeTestHost'

describe('PHASE-07 mounted shell and page templates', () => {
  it('renders the complete visual PortalShell without Router/Session/API behavior', () => {
    const mounted = mountRuntime(PortalShell, {
      portalLabel: '员工端',
      pageTitle: '今日工作台',
      contentId: 'employee-main',
    }, {
      header: () => h('button', '用户菜单'),
      globalAlert: () => h('div', '全局安全提醒'),
      sidebar: () => h('ul', [h('li', '首页'), h('li', '业务')]),
      bottomNav: () => h('div', '移动导航'),
      toastRegion: () => h('div', '提示区域'),
      assistant: () => h('button', '辅助入口'),
      default: () => h('section', '主要工作内容'),
    })

    const skipLink = findByTag(mounted.root, 'a')
    expect(skipLink.getAttribute('href')).toBe('#employee-main')
    expect(findByTag(mounted.root, 'main').getAttribute('id')).toBe('employee-main')
    expect(findByClass(mounted.root, 'sgj-portal-shell__brand').getAttribute('aria-label')).toBe('当前端口：员工端')
    expect(findByClass(mounted.root, 'sgj-portal-shell__global-alert').textContent).toContain('全局安全提醒')
    expect(findByClass(mounted.root, 'sgj-portal-shell__toast-region').textContent).toContain('提示区域')
    expect(findByClass(mounted.root, 'sgj-portal-shell__assistant').textContent).toContain('辅助入口')
    expect(mounted.root.textContent).toContain('主要工作内容')
  })

  it('renders List and Detail template slots through real Vue runtime', () => {
    const list = mountRuntime(ListPageTemplate, {
      title: '记录列表',
      description: '查询全部记录',
    }, {
      actions: () => h('button', '新建'),
      filters: () => h('form', '筛选器'),
      pagination: () => h('nav', '分页'),
      default: () => h('div', '列表内容'),
    })
    expect(list.root.textContent).toContain('筛选器')
    expect(list.root.textContent).toContain('分页')
    expect(list.root.textContent).toContain('列表内容')

    const detail = mountRuntime(DetailPageTemplate, { title: '详情' }, {
      summary: () => h('div', '摘要'),
      aside: () => h('div', '侧栏'),
      timeline: () => h('div', '时间线'),
      default: () => h('div', '详情主体'),
    })
    expect(detail.root.textContent).toContain('摘要')
    expect(detail.root.textContent).toContain('侧栏')
    expect(detail.root.textContent).toContain('时间线')
  })

  it('renders Form and Approval structural slots without business decisions', () => {
    const form = mountRuntime(FormPageTemplate, { title: '表单' }, {
      aside: () => h('div', '填写说明'),
      actions: () => h('button', '提交动作'),
      default: () => h('form', '表单字段'),
    })
    expect(form.root.textContent).toContain('表单字段')
    expect(form.root.textContent).toContain('提交动作')

    const approval = mountRuntime(ApprovalPageTemplate, { title: '专业审核' }, {
      context: () => h('div', '业务上下文'),
      decision: () => h('div', '合法动作区域'),
      timeline: () => h('div', '历史记录'),
      default: () => h('div', '审核事实'),
    })
    expect(approval.root.textContent).toContain('业务上下文')
    expect(approval.root.textContent).toContain('合法动作区域')
    expect(approval.root.textContent).toContain('历史记录')
    expect(approval.root.textContent).toContain('审核事实')
  })

  it('renders Timeline and Dashboard structural slots', () => {
    const timeline = mountRuntime(TimelinePageTemplate, { title: '流程时间线' }, {
      summary: () => h('div', '流程摘要'),
      actions: () => h('button', '允许动作'),
      default: () => h('ol', [h('li', '发起'), h('li', '执行'), h('li', '归档')]),
    })
    expect(timeline.root.textContent).toContain('流程摘要')
    expect(timeline.root.textContent).toContain('归档')

    const dashboard = mountRuntime(DashboardPageTemplate, { title: '经营驾驶舱' }, {
      kpis: () => [h('article', '指标一'), h('article', '指标二')],
      aside: () => h('div', '风险侧栏'),
      actions: () => h('button', '刷新'),
      default: () => h('div', '主图表区域'),
    })
    expect(dashboard.root.textContent).toContain('指标一')
    expect(dashboard.root.textContent).toContain('风险侧栏')
    expect(dashboard.root.textContent).toContain('主图表区域')
  })
})
