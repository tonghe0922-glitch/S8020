import type { ApiProblem } from '../contracts/iam'

export type ApiErrorKind = 'http' | 'transport' | 'timeout' | 'aborted' | 'protocol'

export interface LoginFailurePresentation {
  message: string
  requestId: string
}

function clientRequestId(): string {
  const randomUuid = globalThis.crypto?.randomUUID?.()
  if (randomUuid) return `client-${randomUuid}`
  return `client-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export class ApiClientError extends Error {
  readonly kind: ApiErrorKind
  readonly status?: number
  readonly code?: string
  readonly requestId: string
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
    this.requestId = options.requestId ?? clientRequestId()
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

export function describeLoginFailure(cause: unknown): LoginFailurePresentation {
  if (!(cause instanceof ApiClientError)) {
    return {
      message: '登录失败。请保留下方请求编号，并联系管理员查询服务日志。',
      requestId: clientRequestId(),
    }
  }

  if (cause.code === 'authentication_rejected' || cause.status === 401) {
    return {
      message: '租户编码、账号、密码或 MFA 验证码有误；如账号无有效任职，请联系管理员。',
      requestId: cause.requestId,
    }
  }

  if (cause.code === 'session_store_unavailable') {
    return {
      message: '会话存储（Redis）不可用，请联系管理员，并提供下方请求编号。',
      requestId: cause.requestId,
    }
  }

  if (cause.code === 'security_audit_unavailable') {
    return {
      message: '安全审计服务不可用，请联系管理员，并提供下方请求编号。',
      requestId: cause.requestId,
    }
  }

  if (
    cause.kind === 'transport'
    || cause.kind === 'timeout'
    || cause.status === 502
    || cause.status === 504
  ) {
    return {
      message: '无法连接后端服务。请确认 API 已启动并正确加载 .env，或联系运维查看首次部署手册。',
      requestId: cause.requestId,
    }
  }

  return {
    message: '登录失败。请保留下方请求编号，并联系管理员查询服务日志。',
    requestId: cause.requestId,
  }
}
