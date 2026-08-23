import { describe, expect, it, vi } from 'vitest'
import type { ApiRequestOptions } from '../../api'
import type { usePortalSessionStore } from '../../session'
import { createOrgArchitectureApi } from './org-architecture-api'

type SessionStore = ReturnType<typeof usePortalSessionStore>

describe('organization architecture API client', () => {
  it('uses the canonical same-origin routes and idempotency keys for writes', async () => {
    const request = vi.fn<(path: string, options?: ApiRequestOptions) => Promise<unknown>>()
      .mockResolvedValue({})
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.createDraft('季度组织调整')
    await api.publishDraft('draft-id', 4)

    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/org/drafts', expect.objectContaining({
      method: 'POST',
      body: { title: '季度组织调整' },
      idempotencyKey: expect.stringContaining('org-architecture:draft-create:'),
    }))
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/org/drafts/draft-id/publish', expect.objectContaining({
      method: 'POST',
      body: { expectedVersion: 4 },
      idempotencyKey: expect.stringContaining('org-architecture:draft-publish:'),
    }))
  })

  it('keeps the all-employee directory endpoint read-only', async () => {
    const request = vi.fn<(path: string, options?: ApiRequestOptions) => Promise<unknown>>()
      .mockResolvedValue({ versionNo: 1, organizations: [], members: [] })
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.directory()
    expect(request).toHaveBeenCalledWith('/api/v1/org/directory')
  })
})
