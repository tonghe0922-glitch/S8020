import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import Button from './components/Button.vue'
import Card from './components/Card.vue'
import Conflict from './components/Conflict.vue'
import Empty from './components/Empty.vue'
import ErrorState from './components/Error.vue'
import Input from './components/Input.vue'
import List from './components/List.vue'
import Loading from './components/Loading.vue'
import NoPermission from './components/NoPermission.vue'
import Select from './components/Select.vue'
import StatusChip from './components/StatusChip.vue'
import Table from './components/Table.vue'
import Textarea from './components/Textarea.vue'
import Toast from './components/Toast.vue'
import {
  findByClass,
  findByTag,
  findRuntimeElement,
  mountRuntime,
  triggerRuntimeEvent,
} from './runtimeTestHost'

describe('PHASE-07 mounted primitives and data components', () => {
  it('runs Button loading, disabled and click behavior through Vue runtime', () => {
    const loading = mountRuntime(Button, { loading: true }, { default: () => '保存' })
    const loadingButton = findByTag(loading.root, 'button')
    expect(loadingButton.hasAttribute('disabled')).toBe(true)
    expect(loadingButton.hasAttribute('aria-busy')).toBe(true)
    expect(loading.root.textContent).toContain('处理中')

    const clicks: unknown[] = []
    const active = mountRuntime(Button, {
      variant: 'danger',
      onClick: (event: unknown) => clicks.push(event),
    }, { default: () => '删除' })
    const activeButton = findByTag(active.root, 'button')
    const event = { type: 'click' }
    triggerRuntimeEvent(activeButton, 'click', event)
    expect(clicks).toEqual([event])
  })

  it('renders Card and StatusChip without inventing business state', () => {
    const card = mountRuntime(Card, { variant: 'spotlight', as: 'article' }, {
      default: () => '经营摘要',
    })
    expect(findByTag(card.root, 'article').getAttribute('class')).toContain('sgj-card--spotlight')

    const chip = mountRuntime(StatusChip, { tone: 'warning', ariaLabel: '状态：待复核' }, {
      default: () => '待复核',
    })
    const chipElement = findByClass(chip.root, 'sgj-status-chip')
    expect(chipElement.getAttribute('aria-label')).toBe('状态：待复核')
    expect(chip.root.textContent).toContain('待复核')
  })

  it('keeps Input label, hint, error and v-model contract connected at runtime', () => {
    const updates: string[] = []
    const mounted = mountRuntime(Input, {
      label: '姓名',
      modelValue: '旧值',
      hint: '请输入证件姓名',
      error: '姓名不能为空',
      required: true,
      'onUpdate:modelValue': (value: unknown) => updates.push(String(value)),
    })
    const input = findByTag(mounted.root, 'input')
    const label = findByTag(mounted.root, 'label')
    expect(label.getAttribute('for')).toBe(input.getAttribute('id'))
    expect(input.getAttribute('aria-invalid')).toBe('true')

    const describedBy = input.getAttribute('aria-describedby')?.split(' ') ?? []
    expect(describedBy).toHaveLength(2)
    for (const id of describedBy) {
      expect(findRuntimeElement(mounted.root, (element) => element.getAttribute('id') === id)).toBeTruthy()
    }

    triggerRuntimeEvent(input, 'input', { target: { value: '新值' } } as unknown as Event)
    expect(updates).toEqual(['新值'])
  })

  it('runs Textarea and Select value updates with native field semantics', () => {
    const textUpdates: string[] = []
    const textareaMount = mountRuntime(Textarea, {
      label: '说明',
      modelValue: '',
      'onUpdate:modelValue': (value: unknown) => textUpdates.push(String(value)),
    })
    const textarea = findByTag(textareaMount.root, 'textarea')
    triggerRuntimeEvent(textarea, 'input', { target: { value: '补充说明' } } as unknown as Event)
    expect(textUpdates).toEqual(['补充说明'])

    const selectUpdates: string[] = []
    const selectMount = mountRuntime(Select, {
      label: '类型',
      modelValue: '',
      options: [
        { label: 'A 类', value: 'A' },
        { label: 'B 类', value: 'B', disabled: true },
      ],
      'onUpdate:modelValue': (value: unknown) => selectUpdates.push(String(value)),
    })
    const select = findByTag(selectMount.root, 'select')
    triggerRuntimeEvent(select, 'change', { target: { value: 'A' } } as unknown as Event)
    expect(selectUpdates).toEqual(['A'])
    expect(selectMount.root.textContent).toContain('A 类')
  })

  it('renders Table/List semantics and empty state at runtime', () => {
    const tableMount = mountRuntime(Table, {
      caption: '员工列表',
      empty: true,
      columnCount: 3,
    }, {
      head: () => h('tr', [h('th', '姓名'), h('th', '中心'), h('th', '状态')]),
    })
    const table = findByTag(tableMount.root, 'table')
    expect(table.getAttribute('aria-label')).toBeNull()
    expect(findByTag(tableMount.root, 'caption').textContent).toBe('员工列表')
    expect(findByTag(tableMount.root, 'td').getAttribute('colspan')).toBe('3')
    expect(tableMount.root.textContent).toContain('暂无数据')

    const listMount = mountRuntime(List, { ordered: true, ariaLabel: '步骤' }, {
      default: () => [h('li', '第一步'), h('li', '第二步')],
    })
    const list = findByTag(listMount.root, 'ol')
    expect(list.getAttribute('aria-label')).toBe('步骤')
    expect(listMount.root.textContent).toContain('第二步')
  })

  it('renders operational states, safe error metadata and toast live semantics', () => {
    const empty = mountRuntime(Empty)
    expect(empty.root.textContent).toContain('暂无内容')

    const loading = mountRuntime(Loading)
    const loadingPanel = findByClass(loading.root, 'sgj-state-panel')
    expect(loadingPanel.getAttribute('role')).toBe('status')
    expect(loadingPanel.hasAttribute('aria-busy')).toBe(true)

    const denied = mountRuntime(NoPermission)
    expect(findByClass(denied.root, 'sgj-state-panel').getAttribute('role')).toBe('alert')

    const conflict = mountRuntime(Conflict)
    expect(conflict.root.textContent).toContain('刷新最新事实')

    const error = mountRuntime(ErrorState, {
      errorCode: 'EMPLOYEE_QUERY_FAILED',
      traceId: 'trace-20260808-001',
    })
    expect(error.root.textContent).toContain('EMPLOYEE_QUERY_FAILED')
    expect(error.root.textContent).toContain('trace-20260808-001')
    expect(error.root.textContent).not.toContain('SELECT ')

    const dismissals: string[] = []
    const toast = mountRuntime(Toast, {
      tone: 'danger',
      title: '提交失败',
      message: '请检查后重试',
      onDismiss: () => dismissals.push('dismissed'),
    })
    const toastElement = findByClass(toast.root, 'sgj-toast')
    expect(toastElement.getAttribute('role')).toBe('alert')
    expect(toastElement.getAttribute('aria-live')).toBe('assertive')
    triggerRuntimeEvent(findByTag(toast.root, 'button'), 'click', { type: 'click' })
    expect(dismissals).toEqual(['dismissed'])
  })
})
