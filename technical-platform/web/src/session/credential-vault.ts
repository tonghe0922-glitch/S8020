import type { SessionTokenResponse } from '../contracts'

const STORAGE_KEY = 'sjg.portal.refresh.v1'
const STORAGE_VERSION = 1

export interface StorageAdapter {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
  removeItem: (key: string) => void
}

export interface RefreshCredential {
  refreshToken: string
  refreshExpiresAt: string
}

interface StoredRefreshCredential extends RefreshCredential {
  version: number
}

export interface CredentialVault {
  getAccessToken: () => string | null
  getRefreshCredential: () => RefreshCredential | null
  setTokens: (tokens: SessionTokenResponse) => void
  clear: () => void
}

function validExpiry(value: unknown, now: number): value is string {
  if (typeof value !== 'string') return false
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) && timestamp > now
}

function parseStored(raw: string, now: number): RefreshCredential | null {
  try {
    const value = JSON.parse(raw) as unknown
    if (typeof value !== 'object' || value === null) return null
    const record = value as Record<string, unknown>
    if (record.version !== STORAGE_VERSION) return null
    if (typeof record.refreshToken !== 'string' || !record.refreshToken) return null
    if (!validExpiry(record.refreshExpiresAt, now)) return null
    return {
      refreshToken: record.refreshToken,
      refreshExpiresAt: record.refreshExpiresAt,
    }
  } catch {
    return null
  }
}

export function createCredentialVault(
  storage: StorageAdapter,
  now: () => number = Date.now,
): CredentialVault {
  let accessToken: string | null = null

  function clear(): void {
    accessToken = null
    storage.removeItem(STORAGE_KEY)
  }

  function getRefreshCredential(): RefreshCredential | null {
    const raw = storage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = parseStored(raw, now())
    if (parsed) return parsed
    clear()
    return null
  }

  function setTokens(tokens: SessionTokenResponse): void {
    if (!validExpiry(tokens.refreshExpiresAt, now())) {
      clear()
      throw new Error('refresh credential is already expired')
    }
    accessToken = tokens.accessToken
    const value: StoredRefreshCredential = {
      version: STORAGE_VERSION,
      refreshToken: tokens.refreshToken,
      refreshExpiresAt: tokens.refreshExpiresAt,
    }
    storage.setItem(STORAGE_KEY, JSON.stringify(value))
  }

  return {
    getAccessToken: () => accessToken,
    getRefreshCredential,
    setTokens,
    clear,
  }
}

export function createBrowserCredentialVault(): CredentialVault {
  return createCredentialVault(globalThis.sessionStorage)
}

export const PORTAL_REFRESH_STORAGE_KEY = STORAGE_KEY
