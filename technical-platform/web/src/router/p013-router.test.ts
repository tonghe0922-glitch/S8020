import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P013Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P013Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P013 frozen routes', () => {
  it('binds employee center and tech pages to exact C0 coordinates', async () => {
    const employee = routerFor(PORTALS.work, 'p013.reward.read')
    await employee.push('/employee/08/07/02')
    expect(employee.currentRoute.value.name).toBe('p013-reward-self')

    const center = routerFor(PORTALS.work, 'p013.reward.review')
    await center.push('/center/10/10/02')
    expect(center.currentRoute.value.name).toBe('p013-reward-management')

    const tech = routerFor(PORTALS.tech, 'p013.reward.monitor')
    await tech.push('/tech/06/06/01')
    expect(tech.currentRoute.value.name).toBe('p013-reward-monitor')
  })

  it('denies technical reward metadata without monitor permission', async () => {
    const router = createPortalRouter(PORTALS.tech, new P013Session(), createMemoryHistory())
    await router.push('/tech/06/06/01')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
