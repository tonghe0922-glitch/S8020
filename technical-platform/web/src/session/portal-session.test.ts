import { describe, expect, it } from 'vitest'
import type { StorageAdapter } from './credential-vault'
import { PORTAL_REFRESH_STORAGE_KEY, createCredentialVault } from './credential-vault'
import { createPortalSessionRuntime } from './portal-session-runtime'

class MemoryStorage implements StorageAdapter {
  private readonly values = new Map<string, string>()

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }
}

function future(minutes = 10): string {
  return new Date(Date.now() + minutes * 60_000).toISOString()
}

function tokenBody(accessToken: string, refreshToken: string, identityId = 'identity-a') {
  return {
    accessToken,
    refreshToken,
    accessExpiresAt: future(2),
    refreshExpiresAt: future(10),
    tenantId: 'tenant',
    userId: 'user',
    identityId,
    employeeId: 'employee',
    appointmentId: 'appointment',
    orgId: identityId === 'identity-b' ? 'org-b' : 'org-a',
    positionId: identityId === 'identity-b' ? 'position-b' : 'position-a',
  }
}

function sessionBody(identityId = 'identity-a') {
  return {
    tenantId: 'tenant',
    userId: 'user',
    identityId,
    employeeId: 'employee',
    appointmentId: 'appointment',
    orgId: identityId === 'identity-b' ? 'org-b' : 'org-a',
    positionId: identityId === 'identity-b' ? 'position-b' : 'position-a',
    permissions: identityId === 'identity-b' ? ['portal.read'] : ['portal.read', 'portal.switch'],
    availableIdentities: [
      {
        identityId: 'identity-a', identityType: 'POSITION', identityName: 'Primary',
        orgId: 'org-a', positionId: 'position-a', primary: true,
        effectiveStartAt: null, effectiveEndAt: null,
      },
      {
        identityId: 'identity-b', identityType: 'POSITION', identityName: 'Secondary',
        orgId: 'org-b', positionId: 'position-b', primary: false,
        effectiveStartAt: null, effectiveEndAt: null,
      },
    ],
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function requestPath(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.href
  return input.url
}

function authServer() {
  let refreshCalls = 0
  const fetchFn: typeof fetch = (input, init) => {
    const path = requestPath(input)
    const auth = new Headers(init?.headers).get('Authorization')
    if (path.endsWith('/auth/login')) return Promise.resolve(json({
      ...tokenBody('access-login', 'refresh-login'),
      session: sessionBody(),
    }))
    if (path.endsWith('/auth/refresh')) {
      refreshCalls += 1
      return Promise.resolve(json(tokenBody(`access-refresh-${refreshCalls}`, `refresh-rotated-${refreshCalls}`)))
    }
    if (path.endsWith('/session/switch')) return Promise.resolve(json(tokenBody('access-switch', 'refresh-switch', 'identity-b')))
    if (path.endsWith('/auth/logout')) return Promise.resolve(new Response(null, { status: 204 }))
    if (path.endsWith('/session')) {
      const identity = auth === 'Bearer access-switch' ? 'identity-b' : 'identity-a'
      return Promise.resolve(json(sessionBody(identity)))
    }
    return Promise.resolve(json({ status: 404, code: 'not_found', detail: 'not found', requestId: 'req' }, 404))
  }
  return { fetchFn, refreshCalls: () => refreshCalls }
}

describe('PHASE-08 credential vault', () => {
  it('stores only the refresh envelope and keeps access token in memory', () => {
    const storage = new MemoryStorage()
    const vault = createCredentialVault(storage)
    vault.setTokens(tokenBody('secret-access', 'secret-refresh'))
    const raw = storage.getItem(PORTAL_REFRESH_STORAGE_KEY) ?? ''
    expect(raw).toContain('secret-refresh')
    expect(raw).not.toContain('secret-access')
    expect(vault.getAccessToken()).toBe('secret-access')
  })

  it('fails closed for corrupt and expired tab-scoped refresh state', () => {
    const storage = new MemoryStorage()
    storage.setItem(PORTAL_REFRESH_STORAGE_KEY, '{broken')
    const vault = createCredentialVault(storage)
    expect(vault.getRefreshCredential()).toBeNull()
    expect(storage.getItem(PORTAL_REFRESH_STORAGE_KEY)).toBeNull()

    storage.setItem(PORTAL_REFRESH_STORAGE_KEY, JSON.stringify({
      version: 1,
      refreshToken: 'expired',
      refreshExpiresAt: new Date(Date.now() - 1_000).toISOString(),
    }))
    expect(vault.getRefreshCredential()).toBeNull()
  })
})

describe('PHASE-08 portal session runtime', () => {
  it('logs in, loads authoritative session facts and supports can(permission)', async () => {
    const server = authServer()
    const runtime = createPortalSessionRuntime({ vault: createCredentialVault(new MemoryStorage()), fetchFn: server.fetchFn })
    const session = await runtime.login({ tenantCode: 'tenant', loginName: 'user', password: 'synthetic' })
    expect(session.identityId).toBe('identity-a')
    expect(runtime.snapshot().phase).toBe('authenticated')
    expect(runtime.can('portal.switch')).toBe(true)
  })

  it('restores from refresh state after a new runtime loses the in-memory access token', async () => {
    const storage = new MemoryStorage()
    createCredentialVault(storage).setTokens(tokenBody('old-memory-only', 'refresh-persisted'))
    const server = authServer()
    const runtime = createPortalSessionRuntime({ vault: createCredentialVault(storage), fetchFn: server.fetchFn })
    expect(runtime.getAccessToken()).toBeNull()
    await expect(runtime.restore()).resolves.toBe(true)
    expect(runtime.snapshot().phase).toBe('authenticated')
    expect(runtime.getAccessToken()).toBe('access-refresh-1')
    expect(server.refreshCalls()).toBe(1)
  })

  it('deduplicates concurrent refresh requests into one single-flight operation', async () => {
    const storage = new MemoryStorage()
    createCredentialVault(storage).setTokens(tokenBody('old', 'refresh-persisted'))
    let release: (() => void) | undefined
    let refreshCalls = 0
    const fetchFn: typeof fetch = (input) => {
      const path = requestPath(input)
      if (path.endsWith('/auth/refresh')) {
        refreshCalls += 1
        return new Promise<Response>((resolve) => {
          release = () => resolve(json(tokenBody('access-recovered', 'refresh-recovered')))
        })
      }
      if (path.endsWith('/session')) return Promise.resolve(json(sessionBody()))
      return Promise.resolve(json({}, 404))
    }
    const runtime = createPortalSessionRuntime({ vault: createCredentialVault(storage), fetchFn })
    const first = runtime.recoverSession()
    const second = runtime.recoverSession()
    await Promise.resolve()
    expect(refreshCalls).toBe(1)
    release?.()
    await expect(Promise.all([first, second])).resolves.toEqual([true, true])
  })

  it('switches identity by replacing credentials and refreshing server session facts', async () => {
    const server = authServer()
    const runtime = createPortalSessionRuntime({ vault: createCredentialVault(new MemoryStorage()), fetchFn: server.fetchFn })
    await runtime.login({ tenantCode: 'tenant', loginName: 'user', password: 'synthetic' })
    const switched = await runtime.switchIdentity('identity-b')
    expect(switched.identityId).toBe('identity-b')
    expect(runtime.getAccessToken()).toBe('access-switch')
    expect(runtime.can('portal.switch')).toBe(false)
  })

  it('always clears browser credentials on logout even if server logout fails', async () => {
    const storage = new MemoryStorage()
    const vault = createCredentialVault(storage)
    vault.setTokens(tokenBody('access', 'refresh'))
    const fetchFn: typeof fetch = () => Promise.reject(new TypeError('offline'))
    const runtime = createPortalSessionRuntime({ vault, fetchFn })
    await expect(runtime.logout()).rejects.toMatchObject({ kind: 'transport' })
    expect(runtime.getAccessToken()).toBeNull()
    expect(storage.getItem(PORTAL_REFRESH_STORAGE_KEY)).toBeNull()
    expect(runtime.snapshot().phase).toBe('signed_out')
  })

  it('rejects malformed login session facts and clears newly issued credentials', async () => {
    const storage = new MemoryStorage()
    const vault = createCredentialVault(storage)
    const fetchFn: typeof fetch = (input) => {
      const path = requestPath(input)
      if (path.endsWith('/auth/login')) {
        return Promise.resolve(json({
          ...tokenBody('access', 'refresh'),
          session: { permissions: [] },
        }))
      }
      return Promise.resolve(json({}, 404))
    }
    const runtime = createPortalSessionRuntime({ vault, fetchFn })
    await expect(runtime.login({ tenantCode: 'tenant', loginName: 'user', password: 'synthetic' }))
      .rejects.toMatchObject({ kind: 'protocol' })
    expect(vault.getAccessToken()).toBeNull()
    expect(runtime.snapshot().phase).toBe('error')
  })
})
