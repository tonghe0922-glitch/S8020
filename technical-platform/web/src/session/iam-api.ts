import type { ApiClient } from '../api'
import type {
  LoginBootstrapResponse,
  LoginRequest,
  RefreshRequest,
  SessionTokenResponse,
  SessionView,
  SwitchRequest,
} from '../contracts'
import {
  parseLoginBootstrapResponse,
  parseSessionTokenResponse,
  parseSessionView,
} from './iam-validators'

export interface IamApi {
  login: (request: LoginRequest) => Promise<LoginBootstrapResponse>
  refresh: (request: RefreshRequest) => Promise<SessionTokenResponse>
  current: () => Promise<SessionView>
  switchIdentity: (request: SwitchRequest) => Promise<SessionTokenResponse>
  logout: () => Promise<void>
}

async function tokenCall<TBody>(
  client: ApiClient,
  path: string,
  body: TBody,
): Promise<SessionTokenResponse> {
  const value = await client.request<unknown, TBody>(path, { method: 'POST', body })
  return parseSessionTokenResponse(value)
}

export function createIamApi(publicClient: ApiClient, protectedClient: ApiClient): IamApi {
  return {
    async login(request) {
      const value = await publicClient.request<unknown, LoginRequest>('/api/v1/auth/login', {
        method: 'POST',
        body: request,
      })
      return parseLoginBootstrapResponse(value)
    },
    refresh(request) {
      return tokenCall(publicClient, '/api/v1/auth/refresh', request)
    },
    async current() {
      return parseSessionView(await protectedClient.request<unknown>('/api/v1/session'))
    },
    switchIdentity(request) {
      return tokenCall(protectedClient, '/api/v1/session/switch', request)
    },
    async logout() {
      await protectedClient.request<void>('/api/v1/auth/logout', { method: 'POST' })
    },
  }
}
