import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P005Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

describe('PHASE-09 P005 source-bound routes', () => {
  it('binds the frozen employee receipt route to P005 receipt permissions', async () => {
    const denied = new P005Session()
    const deniedRouter = createPortalRouter(PORTALS.work, denied, createMemoryHistory())
    await deniedRouter.push('/employee/13/01/05')
    expect(deniedRouter.currentRoute.value.name).toBe('forbidden')

    const allowed = new P005Session()
    allowed.permissions.add('p005.notice.receipt')
    const router = createPortalRouter(PORTALS.work, allowed, createMemoryHistory())
    await router.push('/employee/13/01/05')
    expect(router.currentRoute.value.name).toBe('p005-notice-receipt')
  })

  it('binds the frozen center publish route to publish or manage capability', async () => {
    const publisher = new P005Session()
    publisher.permissions.add('p005.notice.publish')
    const publishRouter = createPortalRouter(PORTALS.work, publisher, createMemoryHistory())
    await publishRouter.push('/center/13/01/05')
    expect(publishRouter.currentRoute.value.name).toBe('p005-notice-publish')

    const manager = new P005Session()
    manager.permissions.add('p005.notice.manage')
    const manageRouter = createPortalRouter(PORTALS.work, manager, createMemoryHistory())
    await manageRouter.push('/center/13/01/05')
    expect(manageRouter.currentRoute.value.name).toBe('p005-notice-publish')
  })

  it('keeps the shared tech workflow route backward compatible for P004 and accepts P005 monitor', async () => {
    const p004 = new P005Session()
    p004.permissions.add('p004.request.read')
    const p004Router = createPortalRouter(PORTALS.tech, p004, createMemoryHistory())
    await p004Router.push('/tech/05/03/01')
    expect(p004Router.currentRoute.value.name).toBe('p004-workflow-instance-monitor')

    const p005 = new P005Session()
    p005.permissions.add('p005.notice.monitor')
    const p005Router = createPortalRouter(PORTALS.tech, p005, createMemoryHistory())
    await p005Router.push('/tech/05/03/01')
    expect(p005Router.currentRoute.value.name).toBe('p004-workflow-instance-monitor')
  })
})
