// @vitest-environment happy-dom
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import Avatar from './components/Avatar.vue'
import Cascader from './components/Cascader.vue'
import Checkbox from './components/Checkbox.vue'
import DateTime from './components/DateTime.vue'
import Dialog from './components/Dialog.vue'
import Drawer from './components/Drawer.vue'
import Input from './components/Input.vue'
import KpiCard from './components/KpiCard.vue'
import OrganizationPicker from './components/OrganizationPicker.vue'
import PersonPicker from './components/PersonPicker.vue'
import PersonRow from './components/PersonRow.vue'
import RadioGroup from './components/RadioGroup.vue'
import RecordCard from './components/RecordCard.vue'
import StepUpReveal from './components/StepUpReveal.vue'
import Switch from './components/Switch.vue'
import ToastRegion from './components/ToastRegion.vue'
import Upload from './components/Upload.vue'

describe('PHASE-07 Vue Test Utils component behavior', () => {
  it('connects Input label, hint, error and model update in the DOM', async () => {
    const wrapper = mount(Input, {
      props: {
        label: '姓名',
        modelValue: '',
        hint: '请输入证件姓名',
        error: '姓名不能为空',
        required: true,
      },
      attachTo: document.body,
    })
    const input = wrapper.get('input')
    const describedByValue = input.attributes('aria-describedby')
    expect(describedByValue).toBeDefined()
    if (!describedByValue) throw new Error('Input must expose aria-describedby when hint and error exist')
    const describedBy = describedByValue.split(' ')
    expect(wrapper.get('label').attributes('for')).toBe(input.attributes('id'))
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(describedBy).toHaveLength(2)
    for (const id of describedBy) expect(document.getElementById(id)).not.toBeNull()
    await input.setValue('张三')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['张三'])
    wrapper.unmount()
  })

  it('runs Dialog initial focus, Tab loop, Escape and focus restore', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount(Dialog, {
      props: { open: false, title: '确认操作', description: '请确认后继续' },
      slots: { default: '<button id="dialog-first">确认</button><button id="dialog-last">取消</button>' },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await nextTick()
    await nextTick()
    const panel = wrapper.get('.sgj-dialog')
    const close = wrapper.get('.sgj-overlay__close').element as HTMLButtonElement
    const last = wrapper.get('#dialog-last').element as HTMLButtonElement
    expect(panel.attributes('role')).toBe('dialog')
    expect(panel.attributes('aria-modal')).toBe('true')
    expect(document.activeElement).toBe(close)
    last.focus()
    await panel.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(close)
    await panel.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ open: false })
    await nextTick()
    expect(document.activeElement).toBe(opener)
    wrapper.unmount()
    opener.remove()
  })

  it('runs Drawer keyboard semantics with a real DOM mount', async () => {
    const wrapper = mount(Drawer, {
      props: { open: true, title: '详情', side: 'right' },
      slots: { default: '<button id="drawer-action">处理</button>' },
      attachTo: document.body,
    })
    await nextTick()
    const panel = wrapper.get('.sgj-drawer')
    expect(panel.attributes('role')).toBe('dialog')
    expect(panel.attributes('aria-modal')).toBe('true')
    expect(document.activeElement).toBe(wrapper.get('.sgj-overlay__close').element)
    await panel.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps StepUpReveal fail-closed until the external prop authorizes reveal', async () => {
    const wrapper = mount(StepUpReveal, {
      props: { revealed: false },
      slots: {
        masked: '<span>薪资已隐藏</span>',
        revealed: '<strong id="secret-salary">薪资 12000 元</strong>',
      },
    })
    expect(wrapper.text()).toContain('薪资已隐藏')
    expect(wrapper.find('#secret-salary').exists()).toBe(false)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('requestReveal')).toHaveLength(1)
    expect(wrapper.find('#secret-salary').exists()).toBe(false)
    await wrapper.setProps({ revealed: true })
    expect(wrapper.find('#secret-salary').exists()).toBe(true)
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('conceal')).toHaveLength(1)
  })

  it('covers the DESIGN 11.2 form family with controlled UI contracts', async () => {
    const date = mount(DateTime, { props: { label: '开始时间', mode: 'datetime', modelValue: '' } })
    expect(date.get('input').attributes('type')).toBe('datetime-local')

    const checkbox = mount(Checkbox, { props: { label: '我已确认', modelValue: false } })
    await checkbox.get('input').setValue(true)
    expect(checkbox.emitted('update:modelValue')?.at(-1)).toEqual([true])

    const radio = mount(RadioGroup, {
      props: { label: '班次', modelValue: '', options: [{ value: 'day', label: '白班' }, { value: 'night', label: '夜班' }] },
    })
    const radioInputs = radio.findAll('input')
    const nightRadio = radioInputs[1]
    if (!nightRadio) throw new Error('RadioGroup must render the second option')
    await nightRadio.setValue(true)
    expect(radio.emitted('update:modelValue')?.at(-1)).toEqual(['night'])

    const toggle = mount(Switch, { props: { label: '启用提醒', modelValue: false } })
    await toggle.get('[role="switch"]').trigger('click')
    expect(toggle.emitted('update:modelValue')?.at(-1)).toEqual([true])

    const upload = mount(Upload, { props: { label: '附件', accept: '.pdf', multiple: true } })
    expect(upload.get('input[type="file"]').attributes('multiple')).toBeDefined()

    const cascader = mount(Cascader, {
      props: {
        label: '区域',
        modelValue: [],
        options: [{ value: 'a', label: 'A区', children: [{ value: 'a1', label: 'A1' }] }],
      },
    })
    await cascader.get('select').setValue('a')
    expect(cascader.emitted('update:modelValue')?.at(-1)).toEqual([['a']])

    const people = [{ value: 'employee-1', label: '员工甲' }]
    const person = mount(PersonPicker, { props: { label: '负责人', modelValue: '', options: people } })
    await person.get('select').setValue('employee-1')
    expect(person.emitted('update:modelValue')?.at(-1)).toEqual(['employee-1'])

    const org = mount(OrganizationPicker, { props: { label: '所属组织', modelValue: '', options: [{ value: 'org-1', label: '组织甲' }] } })
    await org.get('select').setValue('org-1')
    expect(org.emitted('update:modelValue')?.at(-1)).toEqual(['org-1'])
  })

  it('covers shared RecordCard, PersonRow, Avatar, KPI and global ToastRegion regressions', async () => {
    const avatar = mount(Avatar, { props: { name: '张三' } })
    expect(avatar.text()).toBe('张三')
    expect(avatar.attributes('role')).toBe('img')

    const person = mount(PersonRow, { props: { name: '李四', subtitle: '运营中心', meta: '在岗' } })
    expect(person.text()).toContain('运营中心')

    const record = mount(RecordCard, { props: { title: '申请记录', subtitle: '2026-08-09' }, slots: { default: '记录正文' } })
    expect(record.text()).toContain('记录正文')

    const kpi = mount(KpiCard, { props: { label: '完成率', value: 98, unit: '%', definition: '已完成 / 应完成' } })
    expect(kpi.text()).toContain('98')
    expect(kpi.text()).toContain('已完成 / 应完成')

    const region = mount(ToastRegion, {
      props: {
        items: [
          { id: 'one', tone: 'info', title: '第一条' },
          { id: 'one', tone: 'danger', title: '重复项' },
          { id: 'two', tone: 'success', title: '第二条' },
        ],
      },
    })
    expect(region.findAll('.sgj-toast')).toHaveLength(2)
    const firstClose = region.findAll('.sgj-toast__close')[0]
    if (!firstClose) throw new Error('ToastRegion must render a dismiss button')
    await firstClose.trigger('click')
    expect(region.emitted('dismiss')?.at(-1)).toEqual(['one'])
  })
})
