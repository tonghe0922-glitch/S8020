import {
  ApiClientError,
  abortedError,
  httpError,
  protocolError,
  timeoutError,
  transportError,
} from './api-error'

export type HttpMethod = 'GET' | 'HEAD' | 'OPTIONS' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface ExplicitStepUpHeader {
  headerName: string
  ticket: string
}

export interface ApiRequestOptions<TBody = unknown> {
  method?: HttpMethod
  body?: TBody
  headers?: Record<string, string>
  signal?: AbortSignal
  timeoutMs?: number
  idempotencyKey?: string
  stepUp?: ExplicitStepUpHeader
}

export interface ApiClientDependencies {
  fetchFn?: typeof fetch
  getAccessToken?: () => string | null
  recoverSession?: () => Promise<boolean>
  defaultTimeoutMs?: number
}

export interface ApiClient {
  request: <TResponse, TBody = unknown>(path: string, options?: ApiRequestOptions<TBody>) => Promise<TResponse>
}

interface AbortScope {
  signal: AbortSignal
  timedOut: () => boolean
  cleanup: () => void
}

interface UnauthorizedAttempt {
  kind: 'unauthorized'
  response: Response
}

interface SuccessAttempt<T> {
  kind: 'success'
  value: T
}

type AttemptResult<T> = UnauthorizedAttempt | SuccessAttempt<T>

const SAFE_METHODS = new Set<HttpMethod>(['GET', 'HEAD', 'OPTIONS'])
const DEFAULT_TIMEOUT_MS = 15_000

function ensureApiPath(path: string): void {
  if (!path.startsWith('/api/')) throw protocolError('API Client 只允许同源 /api/ 路径')
}

function replaySafe(method: HttpMethod, idempotencyKey?: string): boolean {
  return SAFE_METHODS.has(method) || Boolean(idempotencyKey)
}

function buildHeaders(deps: ApiClientDependencies, options: ApiRequestOptions): Headers {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = deps.getAccessToken?.()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey)
  if (options.stepUp) headers.set(options.stepUp.headerName, options.stepUp.ticket)
  return headers
}

function createAbortScope(signal: AbortSignal | undefined, timeoutMs: number): AbortScope {
  const controller = new AbortController()
  let timeoutReached = false
  const timeout = globalThis.setTimeout(() => {
    timeoutReached = true
    controller.abort()
  }, timeoutMs)
  const forwardAbort = () => controller.abort(signal?.reason)
  if (signal?.aborted) forwardAbort()
  else signal?.addEventListener('abort', forwardAbort, { once: true })
  return {
    signal: controller.signal,
    timedOut: () => timeoutReached,
    cleanup: () => {
      globalThis.clearTimeout(timeout)
      signal?.removeEventListener('abort', forwardAbort)
    },
  }
}

function serializeBody(body: unknown): BodyInit | undefined {
  return body === undefined ? undefined : JSON.stringify(body)
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined
  const text = await response.text()
  if (!text) return undefined
  try {
    return JSON.parse(text) as unknown
  } catch (cause) {
    throw protocolError('服务端返回了无法解析的 JSON', cause)
  }
}

async function executeFetch(deps: ApiClientDependencies, path: string, options: ApiRequestOptions): Promise<Response> {
  const timeoutMs = options.timeoutMs ?? deps.defaultTimeoutMs ?? DEFAULT_TIMEOUT_MS
  const scope = createAbortScope(options.signal, timeoutMs)
  try {
    return await (deps.fetchFn ?? fetch)(path, {
      method: options.method ?? 'GET',
      headers: buildHeaders(deps, options),
      body: serializeBody(options.body),
      signal: scope.signal,
    })
  } catch (cause) {
    if (scope.timedOut()) throw timeoutError(cause)
    if (options.signal?.aborted) throw abortedError(cause)
    throw transportError(cause)
  } finally {
    scope.cleanup()
  }
}

async function responseError(response: Response): Promise<ApiClientError> {
  let body: unknown
  try {
    body = await parseBody(response)
  } catch {
    body = undefined
  }
  return httpError(response.status, body)
}

async function requestAttempt<T>(
  deps: ApiClientDependencies,
  path: string,
  options: ApiRequestOptions,
): Promise<AttemptResult<T>> {
  const response = await executeFetch(deps, path, options)
  if (response.status === 401) return { kind: 'unauthorized', response }
  if (!response.ok) throw await responseError(response)
  return { kind: 'success', value: await parseBody(response) as T }
}

function canRetryError(error: ApiClientError, safeToReplay: boolean, attempt: number): boolean {
  return safeToReplay && attempt === 0 && error.retryable
}

async function trySessionRecovery(
  deps: ApiClientDependencies,
  safeToReplay: boolean,
  recovered: boolean,
): Promise<boolean> {
  if (!safeToReplay || recovered || !deps.recoverSession) return false
  return deps.recoverSession()
}

async function handleUnauthorized(
  deps: ApiClientDependencies,
  result: UnauthorizedAttempt,
  safeToReplay: boolean,
  recovered: boolean,
): Promise<'retry'> {
  if (await trySessionRecovery(deps, safeToReplay, recovered)) return 'retry'
  throw await responseError(result.response)
}

async function performRequest<TResponse, TBody>(
  deps: ApiClientDependencies,
  path: string,
  options: ApiRequestOptions<TBody>,
): Promise<TResponse> {
  const safeToReplay = replaySafe(options.method ?? 'GET', options.idempotencyKey)
  let recovered = false
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const result = await requestAttempt<TResponse>(deps, path, options)
      if (result.kind === 'success') return result.value
      await handleUnauthorized(deps, result, safeToReplay, recovered)
      recovered = true
    } catch (error) {
      if (!(error instanceof ApiClientError) || !canRetryError(error, safeToReplay, attempt)) throw error
    }
  }
  throw protocolError('请求重试状态异常')
}

function asError(cause: unknown): Error {
  return cause instanceof Error ? cause : protocolError('未知 API 合同错误', cause)
}

function rejectedContract<T>(cause: unknown): Promise<T> {
  return Promise.reject(asError(cause))
}

export function createApiClient(dependencies: ApiClientDependencies = {}): ApiClient {
  return {
    request<TResponse, TBody = unknown>(path: string, options: ApiRequestOptions<TBody> = {}) {
      try {
        ensureApiPath(path)
      } catch (cause) {
        return rejectedContract<TResponse>(cause)
      }
      return performRequest<TResponse, TBody>(dependencies, path, options)
    },
  }
}
