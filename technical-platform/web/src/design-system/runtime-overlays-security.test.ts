import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import Dialog from './components/Dialog.vue'
import Drawer from './components/Drawer.vue'
import MaskedValue from './components/MaskedValue.vue'
import StepUpReveal from './components/StepUpReveal.vue'
import {
  createExternalFocusable,
  findAllByTag,
  findByClass,
  findByTag,
  mountRuntime,
  runtimeActiveElement,
  runtimeKeyEvent,
  setRuntimeActiveElement,
  triggerRuntimeEvent,
} from './runtimeTestHost'

describe('PHASE-07 mounted overlay and sensitive-data contracts', () => {
  it('runs Dialog focus entry, Tab loop, Escape and focus restore', async () => {
    const closes: string[] = []
    const mounted = mountRuntime(Dialog, {
      open: false,
      title: '确认操作',
      description: '请确认后继续',
      onClose: () => closes.push('close'),
    }, {
      default: () => [
        h('button', { id: 'dialog-primary' }, '确认'),
        h('button', { id: 'dialog-last' }, '取消'),
      ],
    })

    const previous = createExternalFocusable('open-dialog')
    setRuntimeActiveElement(previous)
    await mounted.setProps({ open: true })

    const panel = findByClass(mounted.root, 'sgj-dialog')
    expect(panel.getAttribute('role')).toBe('dialog')
    expect(panel.getAttribute('aria-modal')).toBe('true')
    const buttons = findAllByTag(panel, 'button')
    const first = buttons[0]
    const last = buttons[buttons.length - 1]
    expect(first).toBeDefined()
    expect(last).toBeDefined()
    if (!first || !last) throw new Error('dialog focus targets missing')
    const active = runtimeActiveElement()
    const activeTarget = active
      ? `${active.tag}#${active.getAttribute('id') ?? ''}.${active.getAttribute('class') ?? ''}`
      : 'null'
    const expectedTarget = `${first.tag}#${first.getAttribute('id') ?? ''}.${first.getAttribute('class') ?? ''}`
    expect(
      active === first,
      `Dialog initial focus must enter the first focusable control; active=${activeTarget}; expected=${expectedTarget}`,
    ).toBe(true)

    last.focus()
    const tab = runtimeKeyEvent('Tab')
    triggerRuntimeEvent(panel, 'keydown', tab.event)
    expect(tab.wasPrevented(), 'Dialog forward Tab at the last control must be contained').toBe(true)
    expect(runtimeActiveElement() === first, 'Dialog forward Tab must wrap to the first control').toBe(true)

    first.focus()
    const shiftTab = runtimeKeyEvent('Tab', true)
    triggerRuntimeEvent(panel, 'keydown', shiftTab.event)
    expect(shiftTab.wasPrevented(), 'Dialog Shift+Tab at the first control must be contained').toBe(true)
    expect(runtimeActiveElement() === last, 'Dialog Shift+Tab must wrap to the last control').toBe(true)

    const escape = runtimeKeyEvent('Escape')
    triggerRuntimeEvent(panel, 'keydown', escape.event)
    expect(closes).toEqual(['close'])

    await mounted.setProps({ open: false })
    expect(runtimeActiveElement() === previous, 'Dialog close must restore focus to the previously active control').toBe(true)
  })

  it('runs Drawer dialog semantics and Escape close behavior', async () => {
    const closes: string[] = []
    const mounted = mountRuntime(Drawer, {
      open: false,
      title: '详情',
      side: 'bottom',
      onClose: () => closes.push('close'),
    }, {
      default: () => h('button', { id: 'drawer-action' }, '处理'),
    })

    await mounted.setProps({ open: true })
    const panel = findByClass(mounted.root, 'sgj-drawer')
    expect(panel.getAttribute('class')).toContain('sgj-drawer--bottom')
    expect(panel.getAttribute('role')).toBe('dialog')
    expect(panel.getAttribute('aria-modal')).toBe('true')
    expect(runtimeActiveElement()?.tag).toBe('button')

    triggerRuntimeEvent(panel, 'keydown', runtimeKeyEvent('Escape').event)
    expect(closes).toEqual(['close'])
  })

  it('keeps MaskedValue concealed until external authorization changes the prop', async () => {
    const mounted = mountRuntime(MaskedValue, {
      value: '13800138000',
      mask: '***-***',
    })
    expect(mounted.root.textContent).toContain('***-***')
    expect(mounted.root.textContent).not.toContain('13800138000')

    await mounted.setProps({ revealed: true })
    expect(mounted.root.textContent).toContain('13800138000')

    await mounted.setProps({ revealed: false })
    expect(mounted.root.textContent).not.toContain('13800138000')
  })

  it('does not mount StepUpReveal secret content before external authorization', async () => {
    const requests: string[] = []
    const conceals: string[] = []
    const mounted = mountRuntime(StepUpReveal, {
      revealed: false,
      onRequestReveal: () => requests.push('request'),
      onConceal: () => conceals.push('conceal'),
    }, {
      masked: () => h('span', '薪资已隐藏'),
      revealed: () => h('strong', '薪资 12000 元'),
    })

    expect(mounted.root.textContent).toContain('薪资已隐藏')
    expect(mounted.root.textContent).not.toContain('12000')
    triggerRuntimeEvent(findByTag(mounted.root, 'button'), 'click', { type: 'click' })
    expect(requests).toEqual(['request'])
    expect(mounted.root.textContent).not.toContain('12000')

    await mounted.setProps({ revealed: true })
    expect(mounted.root.textContent).toContain('12000')
    triggerRuntimeEvent(findByTag(mounted.root, 'button'), 'click', { type: 'click' })
    expect(conceals).toEqual(['conceal'])

    await mounted.setProps({ revealed: false, busy: true })
    const busyButton = findByTag(mounted.root, 'button')
    expect(busyButton.hasAttribute('disabled')).toBe(true)
    expect(mounted.root.textContent).toContain('验证中')
    expect(mounted.root.textContent).not.toContain('12000')
  })
})
