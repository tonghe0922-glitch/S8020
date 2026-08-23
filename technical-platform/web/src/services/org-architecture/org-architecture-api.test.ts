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

    const [createDraftPath, createDraftOptions] = request.mock.calls[0] ?? []
    expect(createDraftPath).toBe('/api/v1/org/drafts')
    expect(createDraftOptions?.method).toBe('POST')
    expect(createDraftOptions?.body).toEqual({ title: '季度组织调整' })
    expect(createDraftOptions?.idempotencyKey?.startsWith('org-architecture:draft-create:')).toBe(true)

    const [publishPath, publishOptions] = request.mock.calls[1] ?? []
    expect(publishPath).toBe('/api/v1/org/drafts/draft-id/publish')
    expect(publishOptions?.method).toBe('POST')
    expect(publishOptions?.body).toEqual({ expectedVersion: 4 })
    expect(publishOptions?.idempotencyKey?.startsWith('org-architecture:draft-publish:')).toBe(true)
  })

  it('keeps the all-employee directory endpoint read-only', async () => {
    const request = vi.fn<(path: string, options?: ApiRequestOptions) => Promise<unknown>>()
      .mockResolvedValue({ versionNo: 1, organizations: [], members: [] })
    const api = createOrgArchitectureApi({ request } as unknown as SessionStore)
    await api.directory()
    expect(request).toHaveBeenCalledWith('/api/v1/org/directory')
  })
})
