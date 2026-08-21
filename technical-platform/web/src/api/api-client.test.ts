import { describe, expect, it } from 'vitest'
import { ApiClientError } from './api-error'
import { createApiClient } from './api-client'
import { createRequestFence } from './request-fence'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problemResponse(status: number, code: string): Response {
  return new Response(JSON.stringify({
    status,
    code,
    detail: `problem:${code}`,
    requestId: `request-${status}`,
  }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function abortingFetch(): typeof fetch {
  return (_input, init) => new Promise<Response>((_resolve, reject) => {
    const signal = init?.signal
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  })
}

describe('PHASE-08 unified API client', () => {
  it('injects the current access token and explicit Step-Up header', async () => {
    let observed: RequestInit | undefined
    const fetchFn: typeof fetch = (_input, init) => {
      observed = init
      return Promise.resolve(jsonResponse({ ok: true }))
    }
    const client = createApiClient({ fetchFn, getAccessToken: () => 'access-current' })

    await client.request('/api/v1/session', {
      stepUp: { headerName: 'X-Approved-Step-Up', ticket: 'ticket-value' },
    })

    const headers = new Headers(observed?.headers)
    expect(headers.get('Authorization')).toBe('Bearer access-current')
    expect(headers.get('X-Approved-Step-Up')).toBe('ticket-value')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('maps a 403 problem without entering session recovery', async () => {
    let recoveryCalls = 0
    const client = createApiClient({
      fetchFn: () => Promise.resolve(problemResponse(403, 'forbidden')),
      recoverSession: () => {
        recoveryCalls += 1
        return Promise.resolve(true)
      },
    })

    await expect(client.request('/api/v1/session')).rejects.toMatchObject({
      kind: 'http',
      status: 403,
      code: 'forbidden',
      requestId: 'request-403',
    })
    expect(recoveryCalls).toBe(0)
  })

  it('recovers one safe 401 through a single shared recovery hook', async () => {
    let accessToken = 'old-access'
    let requestCalls = 0
    let recoveryCalls = 0
    const fetchFn: typeof fetch = (_input, init) => {
      requestCalls += 1
      const headers = new Headers(init?.headers)
      const response = headers.get('Authorization') === 'Bearer old-access'
        ? problemResponse(401, 'unauthorized')
        : jsonResponse({ identityId: 'active' })
      return Promise.resolve(response)
    }
    const client = createApiClient({
      fetchFn,
      getAccessToken: () => accessToken,
      recoverSession: () => {
        recoveryCalls += 1
        accessToken = 'new-access'
        return Promise.resolve(true)
      },
    })

    await expect(client.request<{ identityId: string }>('/api/v1/session')).resolves.toEqual({ identityId: 'active' })
    expect(requestCalls).toBe(2)
    expect(recoveryCalls).toBe(1)
  })

  it('does not replay a non-idempotent write without Idempotency-Key', async () => {
    let calls = 0
    const client = createApiClient({
      fetchFn: () => {
        calls += 1
        return Promise.reject(new TypeError('network down'))
      },
    })

    await expect(client.request('/api/v1/session/switch', {
      method: 'POST',
      body: { identityId: 'target' },
    })).rejects.toMatchObject({ kind: 'transport' })
    expect(calls).toBe(1)
  })

  it('replays one explicitly idempotent write and sends the key', async () => {
    let calls = 0
    const keys: Array<string | null> = []
    const client = createApiClient({
      fetchFn: (_input, init) => {
        calls += 1
        keys.push(new Headers(init?.headers).get('Idempotency-Key'))
        const response = calls === 1
          ? problemResponse(503, 'security_audit_unavailable')
          : jsonResponse({ ok: true })
        return Promise.resolve(response)
      },
    })

    await expect(client.request('/api/v1/safe-command', {
      method: 'POST',
      body: { value: 1 },
      idempotencyKey: 'idem-001',
    })).resolves.toEqual({ ok: true })
    expect(calls).toBe(2)
    expect(keys).toEqual(['idem-001', 'idem-001'])
  })

  it('distinguishes timeout from caller cancellation', async () => {
    const timeoutClient = createApiClient({ fetchFn: abortingFetch(), defaultTimeoutMs: 5 })
    await expect(timeoutClient.request('/api/v1/session')).rejects.toMatchObject({ kind: 'timeout' })

    const controller = new AbortController()
    controller.abort('route changed')
    const cancelClient = createApiClient({ fetchFn: abortingFetch() })
    await expect(cancelClient.request('/api/v1/session', { signal: controller.signal }))
      .rejects.toMatchObject({ kind: 'aborted' })
  })

  it('rejects non-api paths and malformed success JSON as protocol errors', async () => {
    const client = createApiClient({ fetchFn: () => Promise.resolve(new Response('not-json', { status: 200 })) })
    await expect(client.request('/internal/session')).rejects.toBeInstanceOf(ApiClientError)
    await expect(client.request('/api/v1/session')).rejects.toMatchObject({ kind: 'protocol' })
  })

  it('supports last-request-wins fencing without cancelling unrelated resources', () => {
    const fence = createRequestFence()
    const first = fence.next()
    const second = fence.next()
    expect(fence.isCurrent(first)).toBe(false)
    expect(fence.isCurrent(second)).toBe(true)
  })
})
