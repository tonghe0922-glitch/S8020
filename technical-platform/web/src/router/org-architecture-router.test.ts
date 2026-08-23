import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class OrgSession implements PortalRouterSession {
  authenticated = true
  permissions = new Set<string>()
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(permission: string): boolean { return this.permissions.has(permission) }
}

describe('organization architecture routes', () => {
  it('keeps employee directory routes available to every authenticated work identity', async () => {
    const router = createPortalRouter(PORTALS.work, new OrgSession(), createMemoryHistory())
    await router.push('/contacts/architecture')
    expect(router.currentRoute.value.name).toBe('org-directory-architecture')
    await router.push('/contacts/directory')
    expect(router.currentRoute.value.name).toBe('org-directory-members')
  })

  it('requires delegated work permission for company architecture management', async () => {
    const denied = createPortalRouter(PORTALS.work, new OrgSession(), createMemoryHistory())
    await denied.push('/center/03/02/09')
    expect(denied.currentRoute.value.name).toBe('forbidden')

    const session = new OrgSession()
    session.permissions.add('org.architecture.edit')
    const allowed = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    await allowed.push('/center/03/02/09')
    expect(allowed.currentRoute.value.name).toBe('org-architecture-manage')
  })

  it('keeps technical direct maintenance behind manage permission', async () => {
    const session = new OrgSession()
    session.permissions.add('org.architecture.manage')
    const router = createPortalRouter(PORTALS.tech, session, createMemoryHistory())
    await router.push('/tech/org')
    expect(router.currentRoute.value.name).toBe('org-architecture-tech')
  })
})
