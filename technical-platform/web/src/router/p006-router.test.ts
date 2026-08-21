import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class P006Session implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

describe('PHASE-10 P006 source-bound routes', () => {
  it('binds both frozen employee meeting/action routes', async () => {
    for (const path of ['/employee/05/01/03', '/employee/05/07/02']) {
      const denied = new P006Session()
      const deniedRouter = createPortalRouter(PORTALS.work, denied, createMemoryHistory())
      await deniedRouter.push(path)
      expect(deniedRouter.currentRoute.value.name).toBe('forbidden')

      const allowed = new P006Session()
      allowed.permissions.add('p006.meeting.action')
      const router = createPortalRouter(PORTALS.work, allowed, createMemoryHistory())
      await router.push(path)
      expect(['p006-meeting-detail', 'p006-action-items']).toContain(router.currentRoute.value.name)
    }
  })

  it('binds both frozen center routes to P006 management capabilities', async () => {
    const creator = new P006Session()
    creator.permissions.add('p006.meeting.create')
    const managementRouter = createPortalRouter(PORTALS.work, creator, createMemoryHistory())
    await managementRouter.push('/center/06/09/03')
    expect(managementRouter.currentRoute.value.name).toBe('p006-meeting-management')

    const manager = new P006Session()
    manager.permissions.add('p006.meeting.manage')
    const ledgerRouter = createPortalRouter(PORTALS.work, manager, createMemoryHistory())
    await ledgerRouter.push('/center/05/02/02')
    expect(ledgerRouter.currentRoute.value.name).toBe('p006-action-ledger')
  })

  it('adds P006 monitoring to the shared technical workflow route without breaking earlier processes', async () => {
    const p006 = new P006Session()
    p006.permissions.add('p006.meeting.monitor')
    const router = createPortalRouter(PORTALS.tech, p006, createMemoryHistory())
    await router.push('/tech/05/03/01')
    expect(router.currentRoute.value.name).toBe('p004-workflow-instance-monitor')
  })
})
