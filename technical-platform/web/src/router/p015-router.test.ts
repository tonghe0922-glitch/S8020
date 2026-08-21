import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P015Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P015Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P015 frozen routes', () => {
  it('binds employee center and tech pages to exact C0 coordinates', async () => {
    const employee = routerFor(PORTALS.work, 'p015.points.read')
    await employee.push('/employee/08/06/04')
    expect(employee.currentRoute.value.name).toBe('p015-points-self-ledger')

    const center = routerFor(PORTALS.work, 'p015.points.review')
    await center.push('/center/10/09/06')
    expect(center.currentRoute.value.name).toBe('p015-points-management')

    const tech = routerFor(PORTALS.tech, 'p015.points.monitor')
    await tech.push('/tech/06/06/03')
    expect(tech.currentRoute.value.name).toBe('p015-points-monitor')
  })

  it('denies technical point metadata without monitor permission', async () => {
    const router = createPortalRouter(PORTALS.tech, new P015Session(), createMemoryHistory())
    await router.push('/tech/06/06/03')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('denies employee immutable-ledger page without read permission', async () => {
    const router = createPortalRouter(PORTALS.work, new P015Session(), createMemoryHistory())
    await router.push('/employee/08/06/04')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
