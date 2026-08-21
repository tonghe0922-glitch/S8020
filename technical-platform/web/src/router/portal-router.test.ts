import { createMemoryHistory } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'
import { currentNavigationSignal, rotateNavigationAbortSignal } from './navigation-abort'
import { safeInternalRedirect } from './redirect'

class FakeSession implements PortalRouterSession {
  authenticated = false
  restoreCalls = 0
  permissions = new Set<string>()

  constructor(private readonly restoreResult = false) {}

  restore(): Promise<boolean> {
    this.restoreCalls += 1
    this.authenticated = this.restoreResult
    return Promise.resolve(this.restoreResult)
  }

  can(permission: string): boolean {
    return this.permissions.has(permission)
  }
}

describe('PHASE-08 portal router guard', () => {
  it('redirects an anonymous protected route to login and preserves intended path', async () => {
    const session = new FakeSession(false)
    const router = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/')
    expect(session.restoreCalls).toBe(1)
  })

  it('restores once and enters the protected shell when refresh succeeds', async () => {
    const session = new FakeSession(true)
    const router = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('portal-home')
    await router.push('/unknown')
    expect(session.restoreCalls).toBe(1)
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('redirects an already authenticated user away from login using only a safe internal path', async () => {
    const session = new FakeSession(true)
    session.authenticated = true
    const router = createPortalRouter(PORTALS.tech, session, createMemoryHistory())
    await router.push({ name: 'login', query: { redirect: '//external.example/path' } })
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('sends a route with unmet permission metadata to forbidden', async () => {
    const session = new FakeSession(true)
    session.authenticated = true
    const router = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    router.addRoute({
      path: '/permission-test',
      name: 'permission-test',
      component: { template: '<div />' },
      meta: { requiresAuth: true, permission: 'portal.special' },
    })
    await router.push('/permission-test')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('supports any-of permission metadata for shared source routes', async () => {
    const denied = new FakeSession(true)
    denied.authenticated = true
    const deniedRouter = createPortalRouter(PORTALS.work, denied, createMemoryHistory())
    await deniedRouter.push('/center/02/01/01')
    expect(deniedRouter.currentRoute.value.name).toBe('forbidden')

    const allowed = new FakeSession(true)
    allowed.authenticated = true
    allowed.permissions.add('p002.request.review')
    const allowedRouter = createPortalRouter(PORTALS.work, allowed, createMemoryHistory())
    await allowedRouter.push('/center/02/01/01')
    expect(allowedRouter.currentRoute.value.name).toBe('phase09-center-inbox')
  })
})

describe('PHASE-09 P001 source-bound routes', () => {
  it('keeps both employee MFA and session source routes on the same server-backed page', async () => {
    const session = new FakeSession(true)
    session.authenticated = true
    const router = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    await router.push('/employee/13/04/04')
    expect(router.currentRoute.value.name).toBe('p001-mfa')
    await router.push('/employee/13/04/06')
    expect(router.currentRoute.value.name).toBe('p001-sessions')
  })

  it('keeps the frozen center route available to a P001 monitor through the shared inbox', async () => {
    const allowed = new FakeSession(true)
    allowed.authenticated = true
    allowed.permissions.add('p001.session.monitor')
    const router = createPortalRouter(PORTALS.work, allowed, createMemoryHistory())
    await router.push('/center/02/01/01')
    expect(router.currentRoute.value.name).toBe('phase09-center-inbox')
  })

  it('binds the frozen tech security monitor route behind the same backend permission', async () => {
    const session = new FakeSession(true)
    session.authenticated = true
    session.permissions.add('p001.session.monitor')
    const router = createPortalRouter(PORTALS.tech, session, createMemoryHistory())
    await router.push('/tech/03/01/01')
    expect(router.currentRoute.value.name).toBe('p001-security-monitor')
  })
})

describe('PHASE-09 P002 source-bound routes', () => {
  it('binds both employee permission request routes and denies identities without P002 permissions', async () => {
    const denied = new FakeSession(true)
    denied.authenticated = true
    const deniedRouter = createPortalRouter(PORTALS.work, denied, createMemoryHistory())
    await deniedRouter.push('/employee/03/07/04')
    expect(deniedRouter.currentRoute.value.name).toBe('forbidden')

    const allowed = new FakeSession(true)
    allowed.authenticated = true
    allowed.permissions.add('p002.request.submit')
    const router = createPortalRouter(PORTALS.work, allowed, createMemoryHistory())
    await router.push('/employee/03/07/04')
    expect(router.currentRoute.value.name).toBe('p002-temporary-permission-request')
    await router.push('/employee/03/07/05')
    expect(router.currentRoute.value.name).toBe('p002-project-permission-request')
  })

  it('allows the shared center inbox with review permission even without P001 monitor permission', async () => {
    const session = new FakeSession(true)
    session.authenticated = true
    session.permissions.add('p002.request.review')
    const router = createPortalRouter(PORTALS.work, session, createMemoryHistory())
    await router.push('/center/02/01/01')
    expect(router.currentRoute.value.name).toBe('phase09-center-inbox')
  })

  it('binds tech execution to the frozen route and accepts execute or revoke capability', async () => {
    const execute = new FakeSession(true)
    execute.authenticated = true
    execute.permissions.add('p002.request.execute')
    const executeRouter = createPortalRouter(PORTALS.tech, execute, createMemoryHistory())
    await executeRouter.push('/tech/03/01/04')
    expect(executeRouter.currentRoute.value.name).toBe('p002-permission-execution')

    const revoke = new FakeSession(true)
    revoke.authenticated = true
    revoke.permissions.add('p002.request.revoke')
    const revokeRouter = createPortalRouter(PORTALS.tech, revoke, createMemoryHistory())
    await revokeRouter.push('/tech/03/01/04')
    expect(revokeRouter.currentRoute.value.name).toBe('p002-permission-execution')
  })
})

describe('PHASE-08 router safety helpers', () => {
  it('rejects external and recursive login redirects', () => {
    expect(safeInternalRedirect('https://example.com')).toBe('/')
    expect(safeInternalRedirect('//example.com')).toBe('/')
    expect(safeInternalRedirect('/login?redirect=/')).toBe('/')
    expect(safeInternalRedirect('/allowed/path?x=1')).toBe('/allowed/path?x=1')
  })

  it('aborts the previous navigation-scoped signal on the next route change', () => {
    const first = rotateNavigationAbortSignal()
    expect(first.aborted).toBe(false)
    const second = rotateNavigationAbortSignal()
    expect(first.aborted).toBe(true)
    expect(second).toBe(currentNavigationSignal())
    expect(second.aborted).toBe(false)
  })
})
