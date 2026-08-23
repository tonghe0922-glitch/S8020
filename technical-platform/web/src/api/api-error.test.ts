import { describe, expect, it } from 'vitest'
import {
  ApiClientError,
  diagnosticRequestId,
  httpError,
  loginFailureMessage,
} from './api-error'

describe('login failure diagnostics', () => {
  it('distinguishes rejected credentials and inactive appointment guidance', () => {
    const error = new ApiClientError('rejected', {
      kind: 'http',
      status: 401,
      code: 'authentication_rejected',
      requestId: 'request-auth',
    })

    expect(loginFailureMessage(error)).toContain('动态验证码')
    expect(loginFailureMessage(error)).toContain('任职状态')
    expect(diagnosticRequestId(error)).toBe('request-auth')
  })

  it('identifies session storage failures without exposing infrastructure names', () => {
    const error = new ApiClientError('redis down', {
      kind: 'http',
      status: 503,
      code: 'session_store_unavailable',
      requestId: 'request-storage',
    })

    expect(loginFailureMessage(error)).toContain('会话存储服务')
    expect(loginFailureMessage(error)).not.toContain('Redis')
  })

  it('identifies fail-closed audit failures', () => {
    const error = new ApiClientError('audit down', {
      kind: 'http',
      status: 503,
      code: 'security_audit_unavailable',
      requestId: 'request-audit',
    })

    expect(loginFailureMessage(error)).toContain('安全审计')
  })

  it('identifies authorization or appointment failures', () => {
    const error = new ApiClientError('forbidden', {
      kind: 'http',
      status: 403,
      code: 'forbidden',
      requestId: 'request-forbidden',
    })

    expect(loginFailureMessage(error)).toContain('身份')
    expect(loginFailureMessage(error)).toContain('任职状态')
  })

  it('uses Chinese network guidance for transport and timeout failures', () => {
    const error = new ApiClientError('timeout', {
      kind: 'timeout',
      retryable: true,
    })

    expect(loginFailureMessage(error)).toContain('网络连接')
    expect(loginFailureMessage(error)).not.toContain('.env')
    expect(loginFailureMessage(error)).not.toContain('BOOTSTRAP')
    expect(diagnosticRequestId(error)).toContain('未到达服务端')
  })

  it('replaces English server details with a safe Chinese message', () => {
    const error = httpError(500, {
      status: 500,
      code: 'internal_error',
      detail: 'relation org.organization does not exist',
      requestId: 'request-internal',
    })

    expect(error.message).toContain('系统服务暂时不可用')
    expect(error.message).not.toContain('relation')
    expect(error.requestId).toBe('request-internal')
  })
})
