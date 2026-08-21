import type { ApiProblem } from '../contracts/iam'

export type ApiErrorKind = 'http' | 'transport' | 'timeout' | 'aborted' | 'protocol'

export class ApiClientError extends Error {
  readonly kind: ApiErrorKind
  readonly status?: number
  readonly code?: string
  readonly requestId?: string
  readonly retryable: boolean

  constructor(
    message: string,
    options: {
      kind: ApiErrorKind
      status?: number
      code?: string
      requestId?: string
      retryable?: boolean
      cause?: unknown
    },
  ) {
    super(message, { cause: options.cause })
    this.name = 'ApiClientError'
    this.kind = options.kind
    this.status = options.status
    this.code = options.code
    this.requestId = options.requestId
    this.retryable = options.retryable ?? false
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

export function isApiProblem(value: unknown): value is ApiProblem {
  if (!isRecord(value)) return false
  return isNumber(value.status)
    && isString(value.code)
    && isString(value.detail)
    && isString(value.requestId)
}

export function httpError(status: number, body: unknown): ApiClientError {
  const problem = isApiProblem(body) ? body : undefined
  const retryable = status === 502 || status === 503 || status === 504
  return new ApiClientError(problem?.detail ?? `HTTP ${status}`, {
    kind: 'http',
    status,
    code: problem?.code,
    requestId: problem?.requestId,
    retryable,
  })
}

export function transportError(cause: unknown): ApiClientError {
  return new ApiClientError('网络请求失败', {
    kind: 'transport',
    retryable: true,
    cause,
  })
}

export function timeoutError(cause?: unknown): ApiClientError {
  return new ApiClientError('请求超时', {
    kind: 'timeout',
    retryable: true,
    cause,
  })
}

export function abortedError(cause?: unknown): ApiClientError {
  return new ApiClientError('请求已取消', {
    kind: 'aborted',
    retryable: false,
    cause,
  })
}

export function protocolError(message: string, cause?: unknown): ApiClientError {
  return new ApiClientError(message, {
    kind: 'protocol',
    retryable: false,
    cause,
  })
}
