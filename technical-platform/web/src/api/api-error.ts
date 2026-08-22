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

export function loginFailureMessage(cause: unknown): string {
  if (!(cause instanceof ApiClientError)) {
    return '登录发生客户端异常，请记录下方请求编号并联系管理员。'
  }

  if (cause.code === 'authentication_rejected' || cause.status === 401) {
    return '租户编码、账号、密码或 MFA 验证码不正确；若信息确认无误，请联系管理员检查账号和在职任职状态。'
  }
  if (cause.code === 'session_store_unavailable') {
    return '会话存储（Redis）不可用，请联系管理员并按《首次部署与登录排障》检查 Redis 服务。'
  }
  if (cause.code === 'security_audit_unavailable') {
    return '安全审计服务不可用，系统已阻止本次登录；请联系管理员并提供下方请求编号。'
  }
  if (cause.status === 403) {
    return '账号身份或在职任职状态已失效，或当前身份无权进入该端口，请联系管理员核验。'
  }
  if (cause.kind === 'transport' || cause.kind === 'timeout' || cause.status === 502 || cause.status === 504) {
    return '无法连接后端服务。请确认 API 已启动并正确加载 .env，再按 docs/BOOTSTRAP.md 的登录排障步骤检查网络。'
  }
  if (cause.status === 503) {
    return '登录依赖服务暂时不可用，请联系管理员并提供下方请求编号。'
  }
  return '暂时无法登录，请记录下方请求编号并联系管理员。'
}

export function diagnosticRequestId(cause: unknown): string {
  if (cause instanceof ApiClientError && cause.requestId) return cause.requestId
  if (cause instanceof ApiClientError && (cause.kind === 'transport' || cause.kind === 'timeout')) {
    return '未生成（请求未到达服务端）'
  }
  return '未生成（客户端异常）'
}
