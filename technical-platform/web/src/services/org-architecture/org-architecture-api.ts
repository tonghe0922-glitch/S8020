import type {
  OrgArchitectureDirectory,
  OrgArchitectureDraft,
  OrgArchitectureNode,
  OrgArchitectureNodeCommand,
  OrgArchitectureToggleCommand,
} from '../../contracts'
import type { usePortalSessionStore } from '../../session'

type SessionStore = ReturnType<typeof usePortalSessionStore>

export interface OrgArchitectureApi {
  tree: () => Promise<OrgArchitectureNode[]>
  directory: () => Promise<OrgArchitectureDirectory>
  createNode: (command: OrgArchitectureNodeCommand) => Promise<OrgArchitectureNode>
  updateNode: (nodeId: string, command: OrgArchitectureNodeCommand) => Promise<OrgArchitectureNode>
  toggleNode: (nodeId: string, command: OrgArchitectureToggleCommand) => Promise<OrgArchitectureNode>
  deleteNode: (nodeId: string) => Promise<OrgArchitectureNode>
  draft: (draftId: string) => Promise<OrgArchitectureDraft>
  latestEditableDraft: () => Promise<OrgArchitectureDraft>
  pendingDrafts: () => Promise<OrgArchitectureDraft[]>
  createDraft: (title: string) => Promise<OrgArchitectureDraft>
  updateDraft: (
    draftId: string,
    title: string,
    expectedVersion: number,
    snapshot: OrgArchitectureNode[],
  ) => Promise<OrgArchitectureDraft>
  submitDraft: (draftId: string, expectedVersion: number) => Promise<OrgArchitectureDraft>
  reviewDraft: (
    draftId: string,
    expectedVersion: number,
    approved: boolean,
    comment: string,
  ) => Promise<OrgArchitectureDraft>
  publishDraft: (draftId: string, expectedVersion: number) => Promise<OrgArchitectureDraft>
  abandonDraft: (draftId: string, expectedVersion: number) => Promise<OrgArchitectureDraft>
}

function idempotencyKey(action: string): string {
  const suffix = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `org-architecture:${action}:${suffix}`
}

function encoded(value: string): string {
  return encodeURIComponent(value)
}

export function createOrgArchitectureApi(session: SessionStore): OrgArchitectureApi {
  const request = session.request
  return {
    tree: () => request('/api/v1/org/tree'),
    directory: () => request('/api/v1/org/directory'),
    createNode: (body) => request('/api/v1/org/nodes', {
      method: 'POST', body, idempotencyKey: idempotencyKey('node-create'),
    }),
    updateNode: (nodeId, body) => request(`/api/v1/org/nodes/${encoded(nodeId)}`, {
      method: 'PUT', body, idempotencyKey: idempotencyKey('node-update'),
    }),
    toggleNode: (nodeId, body) => request(`/api/v1/org/nodes/${encoded(nodeId)}/toggle`, {
      method: 'POST', body, idempotencyKey: idempotencyKey('node-toggle'),
    }),
    deleteNode: (nodeId) => request(`/api/v1/org/nodes/${encoded(nodeId)}`, {
      method: 'DELETE', idempotencyKey: idempotencyKey('node-delete'),
    }),
    draft: (draftId) => request(`/api/v1/org/drafts/${encoded(draftId)}`),
    latestEditableDraft: () => request('/api/v1/org/drafts/latest-editable'),
    pendingDrafts: () => request('/api/v1/org/drafts/pending'),
    createDraft: (title) => request('/api/v1/org/drafts', {
      method: 'POST', body: { title }, idempotencyKey: idempotencyKey('draft-create'),
    }),
    updateDraft: (draftId, title, expectedVersion, snapshot) => request(
      `/api/v1/org/drafts/${encoded(draftId)}`,
      {
        method: 'PUT',
        body: { title, expectedVersion, snapshot },
        idempotencyKey: idempotencyKey('draft-update'),
      },
    ),
    submitDraft: (draftId, expectedVersion) => request(`/api/v1/org/drafts/${encoded(draftId)}/submit`, {
      method: 'POST', body: { expectedVersion }, idempotencyKey: idempotencyKey('draft-submit'),
    }),
    reviewDraft: (draftId, expectedVersion, approved, comment) => request(
      `/api/v1/org/drafts/${encoded(draftId)}/review`,
      {
        method: 'POST',
        body: { expectedVersion, approved, comment },
        idempotencyKey: idempotencyKey('draft-review'),
      },
    ),
    publishDraft: (draftId, expectedVersion) => request(`/api/v1/org/drafts/${encoded(draftId)}/publish`, {
      method: 'POST', body: { expectedVersion }, idempotencyKey: idempotencyKey('draft-publish'),
    }),
    abandonDraft: (draftId, expectedVersion) => request(`/api/v1/org/drafts/${encoded(draftId)}/abandon`, {
      method: 'POST', body: { expectedVersion }, idempotencyKey: idempotencyKey('draft-abandon'),
    }),
  }
}
