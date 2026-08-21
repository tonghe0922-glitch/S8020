// @vitest-environment happy-dom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { SessionView } from '../contracts'
import { PORTALS } from './portal-config'
import PortalSessionHeader from './PortalSessionHeader.vue'

function session(permissions: string[] = ['portal.read', 'platform.session.switch']): SessionView {
  return {
    tenantId: 'tenant',
    userId: 'user',
    identityId: 'identity-a',
    employeeId: 'employee',
    appointmentId: 'appointment',
    orgId: 'org-a',
    positionId: 'position-a',
    permissions,
    availableIdentities: [
      {
        identityId: 'identity-a', identityType: 'POSITION', identityName: '主岗位',
        orgId: 'org-a', positionId: 'position-a', primary: true,
        effectiveStartAt: null, effectiveEndAt: null,
      },
      {
        identityId: 'identity-b', identityType: 'POSITION', identityName: '兼任岗位',
        orgId: 'org-b', positionId: 'position-b', primary: false,
        effectiveStartAt: null, effectiveEndAt: null,
      },
    ],
  }
}

describe('PHASE-08 PortalSessionHeader', () => {
  it('renders the current server identity and emits only explicit switch/logout intents', async () => {
    const wrapper = mount(PortalSessionHeader, {
      props: { portal: PORTALS.work, session: session() },
    })
    expect(wrapper.text()).toContain('工作端')
    expect(wrapper.text()).toContain('主岗位')

    const select = wrapper.get('select')
    await select.setValue('identity-b')
    expect(wrapper.emitted('switchIdentity')).toEqual([['identity-b']])

    const logout = wrapper.findAll('button').find((button) => button.text().includes('退出登录'))
    expect(logout).toBeDefined()
    await logout?.trigger('click')
    expect(wrapper.emitted('logout')).toHaveLength(1)
  })

  it('does not emit a switch when the current identity is selected again', async () => {
    const wrapper = mount(PortalSessionHeader, {
      props: { portal: PORTALS.work, session: session() },
    })
    await wrapper.get('select').setValue('identity-a')
    expect(wrapper.emitted('switchIdentity')).toBeUndefined()
  })

  it('hides the switch control when the server did not grant switch permission', () => {
    const wrapper = mount(PortalSessionHeader, {
      props: { portal: PORTALS.tech, session: session(['portal.read']) },
    })
    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.text()).toContain('主岗位')
    expect(wrapper.text()).toContain('退出登录')
  })
})
