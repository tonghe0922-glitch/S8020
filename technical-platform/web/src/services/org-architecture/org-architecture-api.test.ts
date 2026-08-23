import { describe, expect, it, vi } from 'vitest'
import type { ApiRequestOptions } from '../../api'
import type { usePortalSessionStore } from '../../session'
import { createOrgArchitectureApi } from './org-architecture-api'

type SessionStore = ReturnType<typeof usePortalSessionStore>
type RequestMock = ReturnType<typeof vi.fn<(path: string, options?: ApiRequestOptions) => Promise<unknown>>>

function requestMock(): RequestMock {
  return vi.fn<(path: string, options?: ApiRequestOptions) => Promise<unknown>>().mockResolvedValue({})
}

function requestOptions(request: RequestMock): ApiRequestOptions {
  return request.mock.calls[0]?.[1] ?? {}
}

describe('organization architecture API client', () => {
  it('creates drafts on the canonical route with an idempotency key', async () => {
    const request = requestMock()
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.createDraft('季度组织调整')

    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/org/drafts')
    expect(requestOptions(request)).toMatchObject({
      method: 'POST',
      body: { title: '季度组织调整' },
    })
    expect(requestOptions(request).idempotencyKey?.startsWith('org-architecture:draft-create:')).toBe(true)
  })

  it('publishes drafts on the canonical route with an idempotency key', async () => {
    const request = requestMock()
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.publishDraft('draft-id', 4)

    expect(request.mock.calls[0]?.[0]).toBe('/api/v1/org/drafts/draft-id/publish')
    expect(requestOptions(request)).toMatchObject({
      method: 'POST',
      body: { expectedVersion: 4 },
    })
    expect(requestOptions(request).idempotencyKey?.startsWith('org-architecture:draft-publish:')).toBe(true)
  })

  it('keeps the all-employee directory endpoint read-only', async () => {
    const request = requestMock().mockResolvedValue({ versionNo: 1, organizations: [], members: [] })
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.directory()
    expect(request).toHaveBeenCalledWith('/api/v1/org/directory')
  })
})
