import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P011Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P011Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P011 frozen routes', () => {
  it('binds employee, center and tech routes to dedicated P011 pages', async () => {
    const employee = routerFor(PORTALS.work, 'p011.performance.self')
    await employee.push('/employee/08/01/01')
    expect(employee.currentRoute.value.name).toBe('p011-performance-self')

    const center = routerFor(PORTALS.work, 'p011.performance.evaluate')
    await center.push('/center/10/01/01')
    expect(center.currentRoute.value.name).toBe('p011-performance-management')

    const tech = routerFor(PORTALS.tech, 'p011.performance.monitor')
    await tech.push('/tech/06/05/01')
    expect(tech.currentRoute.value.name).toBe('p011-performance-monitor')
  })

  it('denies the P011 technical page without monitor permission', async () => {
    const session = new P011Session()
    const router = createPortalRouter(PORTALS.tech, session, createMemoryHistory())
    await router.push('/tech/06/05/01')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
