import { shallowRef } from 'vue'
import { ApiClientError } from '../api'

export interface RuntimeFailure {
  message: string
  requestId?: string
}

const runtimeFailure = shallowRef<RuntimeFailure | null>(null)

export function recordRuntimeError(cause: unknown): void {
  if (cause instanceof ApiClientError) {
    runtimeFailure.value = {
      message: cause.status === 403 ? '当前身份无权完成该操作。' : '页面运行出现异常，请重试。',
      requestId: cause.requestId,
    }
    return
  }
  runtimeFailure.value = { message: cause instanceof Error ? '页面运行出现异常，请重试。' : '发生未知页面异常。' }
}

export function clearRuntimeError(): void {
  runtimeFailure.value = null
}

export function useRuntimeErrorState() {
  return runtimeFailure
}
