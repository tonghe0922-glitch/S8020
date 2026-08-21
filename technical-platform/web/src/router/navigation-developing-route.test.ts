import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class AuthenticatedSession implements PortalRouterSession {
  authenticated = true
  restore(): Promise<boolean> { return Promise.resolve(true) }
  can(): boolean { return false }
}

describe('navigation developing route', () => {
  it('keeps the placeholder inside the authenticated portal shell without a fake business permission', async () => {
    const router = createPortalRouter(PORTALS.work, new AuthenticatedSession(), createMemoryHistory())
    await router.push('/developing?module=shows-program&label=节目单&group=演出节目')
    expect(router.currentRoute.value.name).toBe('navigation-developing')
    expect(router.currentRoute.value.query.module).toBe('shows-program')
    expect(router.currentRoute.value.matched.some((record) => record.meta.requiresAuth)).toBe(true)
  })
})
