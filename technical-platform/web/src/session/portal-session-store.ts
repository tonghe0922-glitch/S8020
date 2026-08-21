import { computed, ref, shallowRef } from 'vue'
import type { Ref, ShallowRef } from 'vue'
import { defineStore } from 'pinia'
import type { ApiRequestOptions } from '../api'
import type { LoginRequest, SessionView } from '../contracts'
import type { PortalSessionPhase, PortalSessionRuntime } from './portal-session-runtime'
import { createBrowserPortalSessionRuntime } from './portal-session-runtime'

function syncState(
  runtime: PortalSessionRuntime,
  phase: Ref<PortalSessionPhase>,
  session: ShallowRef<SessionView | null>,
  error: ShallowRef<Error | null>,
): void {
  const snapshot = runtime.snapshot()
  phase.value = snapshot.phase
  session.value = snapshot.session
  error.value = snapshot.error
}

export const usePortalSessionStore = defineStore('portal-session', () => {
  const runtime = createBrowserPortalSessionRuntime()
  const phase = ref<PortalSessionPhase>(runtime.snapshot().phase)
  const session = shallowRef<SessionView | null>(runtime.snapshot().session)
  const error = shallowRef<Error | null>(runtime.snapshot().error)
  const authenticated = computed(() => phase.value === 'authenticated' && session.value !== null)

  async function run<T>(operation: () => Promise<T>): Promise<T> {
    try {
      return await operation()
    } finally {
      syncState(runtime, phase, session, error)
    }
  }

  return {
    phase,
    session,
    error,
    authenticated,
    login: (request: LoginRequest) => run(() => runtime.login(request)),
    restore: () => run(() => runtime.restore()),
    recoverSession: () => run(() => runtime.recoverSession()),
    logout: () => run(() => runtime.logout()),
    switchIdentity: (identityId: string) => run(() => runtime.switchIdentity(identityId)),
    can: (permission: string) => runtime.can(permission),
    request: <TResponse, TBody = unknown>(path: string, options: ApiRequestOptions<TBody> = {}) =>
      runtime.request<TResponse, TBody>(path, options),
  }
})
