import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P012Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P012Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P012 frozen routes', () => {
  it('binds employee, center and tech routes to dedicated P012 pages', async () => {
    const employee = routerFor(PORTALS.work, 'p012.promotion.read')
    await employee.push('/employee/03/03/05')
    expect(employee.currentRoute.value.name).toBe('p012-promotion-self')

    const center = routerFor(PORTALS.work, 'p012.promotion.review')
    await center.push('/center/10/06/01')
    expect(center.currentRoute.value.name).toBe('p012-promotion-management')

    const tech = routerFor(PORTALS.tech, 'p012.promotion.monitor')
    await tech.push('/tech/01/11/07')
    expect(tech.currentRoute.value.name).toBe('p012-promotion-monitor')
  })

  it('denies the P012 technical page without monitor permission', async () => {
    const session = new P012Session()
    const router = createPortalRouter(PORTALS.tech, session, createMemoryHistory())
    await router.push('/tech/01/11/07')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
