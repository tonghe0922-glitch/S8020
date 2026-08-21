import { describe, expect, it } from 'vitest'
import type { StorageAdapter } from './credential-vault'
import { createCredentialVault } from './credential-vault'
import { createPortalSessionRuntime } from './portal-session-runtime'

class MemoryStorage implements StorageAdapter {
  private readonly values = new Map<string, string>()
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

function future(): string {
  return new Date(Date.now() + 600_000).toISOString()
}

function tokenResponse() {
  return {
    accessToken: 'access-a', refreshToken: 'refresh-a', accessExpiresAt: future(), refreshExpiresAt: future(),
    tenantId: 'tenant', userId: 'user', identityId: 'identity-a', employeeId: 'employee',
    appointmentId: 'appointment', orgId: 'org-a', positionId: 'position-a',
  }
}

function sessionResponse() {
  return {
    tenantId: 'tenant', userId: 'user', identityId: 'identity-a', employeeId: 'employee',
    appointmentId: 'appointment', orgId: 'org-a', positionId: 'position-a', permissions: ['portal.switch'],
    availableIdentities: [{
      identityId: 'identity-a', identityType: 'POSITION', identityName: '主岗位', orgId: 'org-a',
      positionId: 'position-a', primary: true, effectiveStartAt: null, effectiveEndAt: null,
    }],
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

describe('PHASE-08 identity switch failure boundary', () => {
  it('preserves the previous valid session when the switch command itself is rejected', async () => {
    const fetchFn: typeof fetch = (input) => {
      const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (path.endsWith('/auth/login')) {
        return Promise.resolve(json({ ...tokenResponse(), session: sessionResponse() }))
      }
      if (path.endsWith('/session/switch')) {
        return Promise.resolve(json({ status: 403, code: 'forbidden', detail: 'denied', requestId: 'req-switch' }, 403))
      }
      if (path.endsWith('/session')) return Promise.resolve(json(sessionResponse()))
      return Promise.resolve(json({}, 404))
    }
    const runtime = createPortalSessionRuntime({ vault: createCredentialVault(new MemoryStorage()), fetchFn })
    await runtime.login({ tenantCode: 'tenant', loginName: 'user', password: 'synthetic' })

    await expect(runtime.switchIdentity('identity-b')).rejects.toMatchObject({ status: 403 })
    expect(runtime.snapshot().phase).toBe('authenticated')
    expect(runtime.snapshot().session?.identityId).toBe('identity-a')
    expect(runtime.getAccessToken()).toBe('access-a')
    expect(runtime.snapshot().error).toBeTruthy()
  })
})
