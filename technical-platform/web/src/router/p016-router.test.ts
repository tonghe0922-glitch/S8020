import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P016Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P016Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P016 frozen routes', () => {
  it('binds employee center and tech pages to exact C0 coordinates', async () => {
    const employee = routerFor(PORTALS.work, 'p016.care.confirm')
    await employee.push('/employee/03/06/05')
    expect(employee.currentRoute.value.name).toBe('p016-care-self-service')

    const center = routerFor(PORTALS.work, 'p016.care.review')
    await center.push('/center/06/03/09')
    expect(center.currentRoute.value.name).toBe('p016-care-management')

    const tech = routerFor(PORTALS.tech, 'p016.care.monitor')
    await tech.push('/tech/01/11/04')
    expect(tech.currentRoute.value.name).toBe('p016-care-monitor')
  })

  it('allows employee application page with create permission only', async () => {
    const employee = routerFor(PORTALS.work, 'p016.care.create')
    await employee.push('/employee/03/06/05')
    expect(employee.currentRoute.value.name).toBe('p016-care-self-service')
  })

  it('denies technical welfare metadata without monitor permission', async () => {
    const router = createPortalRouter(PORTALS.tech, new P016Session(), createMemoryHistory())
    await router.push('/tech/01/11/04')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('denies employee welfare page without any frozen P016 permission', async () => {
    const router = createPortalRouter(PORTALS.work, new P016Session(), createMemoryHistory())
    await router.push('/employee/03/06/05')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
