import type { ApiClient, ApiRequestOptions } from '../api'
import { ApiClientError, createApiClient } from '../api'
import type { LoginRequest, SessionTokenResponse, SessionView } from '../contracts'
import type { CredentialVault } from './credential-vault'
import { createBrowserCredentialVault } from './credential-vault'
import type { IamApi } from './iam-api'
import { createIamApi } from './iam-api'

export type PortalSessionPhase =
  | 'anonymous'
  | 'authenticating'
  | 'restoring'
  | 'authenticated'
  | 'refreshing'
  | 'switching'
  | 'expired'
  | 'signed_out'
  | 'error'

export interface PortalSessionSnapshot {
  phase: PortalSessionPhase
  session: SessionView | null
  error: Error | null
}

export interface PortalSessionDependencies {
  vault: CredentialVault
  fetchFn?: typeof fetch
}

export interface PortalSessionRuntime {
  snapshot: () => PortalSessionSnapshot
  getAccessToken: () => string | null
  login: (request: LoginRequest) => Promise<SessionView>
  restore: () => Promise<boolean>
  recoverSession: () => Promise<boolean>
  logout: () => Promise<void>
  switchIdentity: (identityId: string) => Promise<SessionView>
  can: (permission: string) => boolean
  request: <TResponse, TBody = unknown>(path: string, options?: ApiRequestOptions<TBody>) => Promise<TResponse>
}

function sessionError(cause: unknown): Error {
  return cause instanceof Error ? cause : new Error('未知会话错误')
}

class DefaultPortalSessionRuntime implements PortalSessionRuntime {
  private phase: PortalSessionPhase = 'anonymous'
  private currentSession: SessionView | null = null
  private lastError: Error | null = null
  private refreshPromise: Promise<boolean> | null = null
  private readonly vault: CredentialVault
  private readonly iam: IamApi
  private readonly businessClient: ApiClient

  constructor(deps: PortalSessionDependencies) {
    this.vault = deps.vault
    const publicClient = createApiClient({ fetchFn: deps.fetchFn })
    const protectedClient = createApiClient({
      fetchFn: deps.fetchFn,
      getAccessToken: () => this.vault.getAccessToken(),
    })
    this.businessClient = createApiClient({
      fetchFn: deps.fetchFn,
      getAccessToken: () => this.vault.getAccessToken(),
      recoverSession: () => this.recoverSession(),
    })
    this.iam = createIamApi(publicClient, protectedClient)
  }

  snapshot(): PortalSessionSnapshot {
    return { phase: this.phase, session: this.currentSession, error: this.lastError }
  }

  getAccessToken(): string | null {
    return this.vault.getAccessToken()
  }

  async login(request: LoginRequest): Promise<SessionView> {
    this.vault.clear()
    this.currentSession = null
    this.setPhase('authenticating')
    try {
      const bootstrap = await this.iam.login(request)
      return await this.establish(bootstrap, true, bootstrap.session)
    } catch (cause) {
      this.fail(cause, true)
      throw sessionError(cause)
    }
  }

  async restore(): Promise<boolean> {
    if (!this.vault.getRefreshCredential()) {
      this.setPhase('anonymous')
      return false
    }
    this.setPhase('restoring')
    return this.recoverSession()
  }

  recoverSession(): Promise<boolean> {
    if (this.refreshPromise) return this.refreshPromise
    const operation = this.refreshFromVault().finally(() => {
      if (this.refreshPromise === operation) this.refreshPromise = null
    })
    this.refreshPromise = operation
    return operation
  }

  async logout(): Promise<void> {
    let failure: unknown
    try {
      if (this.vault.getAccessToken()) await this.iam.logout()
    } catch (cause) {
      failure = cause
    } finally {
      this.vault.clear()
      this.currentSession = null
      this.lastError = null
      this.phase = 'signed_out'
    }
    if (failure !== undefined) throw sessionError(failure)
  }

  async switchIdentity(identityId: string): Promise<SessionView> {
    const previousSession = this.currentSession
    this.setPhase('switching')
    let tokens: SessionTokenResponse
    try {
      tokens = await this.iam.switchIdentity({ identityId })
    } catch (cause) {
      this.currentSession = previousSession
      this.lastError = sessionError(cause)
      this.phase = previousSession ? 'authenticated' : 'error'
      throw sessionError(cause)
    }
    this.currentSession = null
    return this.establish(tokens, false)
  }

  can(permission: string): boolean {
    return this.currentSession?.permissions.includes(permission) ?? false
  }

  request<TResponse, TBody = unknown>(path: string, options: ApiRequestOptions<TBody> = {}): Promise<TResponse> {
    return this.businessClient.request<TResponse, TBody>(path, options)
  }

  private async refreshFromVault(): Promise<boolean> {
    const refresh = this.vault.getRefreshCredential()
    if (!refresh) return this.expire()
    this.setPhase('refreshing')
    try {
      const tokens = await this.iam.refresh({ refreshToken: refresh.refreshToken })
      await this.establish(tokens, false)
      return true
    } catch (cause) {
      if (cause instanceof ApiClientError && cause.status === 401) return this.expire()
      this.fail(cause, false)
      throw sessionError(cause)
    }
  }

  private async establish(
    tokens: SessionTokenResponse,
    clearOnFailure: boolean,
    bootstrapSession?: SessionView,
  ): Promise<SessionView> {
    this.vault.setTokens(tokens)
    try {
      const session = bootstrapSession ?? await this.iam.current()
      this.currentSession = session
      this.lastError = null
      this.phase = 'authenticated'
      return session
    } catch (cause) {
      this.fail(cause, clearOnFailure)
      throw sessionError(cause)
    }
  }

  private expire(): false {
    this.vault.clear()
    this.currentSession = null
    this.lastError = null
    this.phase = 'expired'
    return false
  }

  private fail(cause: unknown, clearCredentials: boolean): void {
    if (clearCredentials) this.vault.clear()
    this.currentSession = null
    this.lastError = sessionError(cause)
    this.phase = 'error'
  }

  private setPhase(phase: PortalSessionPhase): void {
    this.phase = phase
    this.lastError = null
  }
}

export function createPortalSessionRuntime(deps: PortalSessionDependencies): PortalSessionRuntime {
  return new DefaultPortalSessionRuntime(deps)
}

export function createBrowserPortalSessionRuntime(): PortalSessionRuntime {
  return createPortalSessionRuntime({ vault: createBrowserCredentialVault() })
}
