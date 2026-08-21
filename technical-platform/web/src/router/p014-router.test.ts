import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P014Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

function routerFor(portal: typeof PORTALS.work, permission: string) {
  const session = new P014Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

describe('PHASE-11 P014 frozen routes', () => {
  it('binds employee center and tech pages to exact C0 coordinates', async () => {
    const employee = routerFor(PORTALS.work, 'p014.discipline.appeal')
    await employee.push('/employee/02/03/09')
    expect(employee.currentRoute.value.name).toBe('p014-discipline-self-service')

    const center = routerFor(PORTALS.work, 'p014.discipline.investigate')
    await center.push('/center/12/02/04')
    expect(center.currentRoute.value.name).toBe('p014-discipline-management')

    const tech = routerFor(PORTALS.tech, 'p014.discipline.monitor')
    await tech.push('/tech/06/06/02')
    expect(tech.currentRoute.value.name).toBe('p014-discipline-monitor')
  })

  it('denies technical discipline metadata without monitor permission', async () => {
    const router = createPortalRouter(PORTALS.tech, new P014Session(), createMemoryHistory())
    await router.push('/tech/06/06/02')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('denies employee discipline page without self-case read or appeal permission', async () => {
    const router = createPortalRouter(PORTALS.work, new P014Session(), createMemoryHistory())
    await router.push('/employee/02/03/09')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
