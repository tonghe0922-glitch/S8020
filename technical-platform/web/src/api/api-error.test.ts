import { describe, expect, it } from 'vitest'
import {
  ApiClientError,
  diagnosticRequestId,
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

    expect(loginFailureMessage(error)).toContain('MFA')
    expect(loginFailureMessage(error)).toContain('任职状态')
    expect(diagnosticRequestId(error)).toBe('request-auth')
  })

  it('identifies Redis session storage failures', () => {
    const error = new ApiClientError('redis down', {
      kind: 'http',
      status: 503,
      code: 'session_store_unavailable',
      requestId: 'request-redis',
    })

    expect(loginFailureMessage(error)).toContain('Redis')
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

  it('gives bootstrap guidance for transport and timeout failures', () => {
    const error = new ApiClientError('timeout', {
      kind: 'timeout',
      retryable: true,
    })

    expect(loginFailureMessage(error)).toContain('.env')
    expect(loginFailureMessage(error)).toContain('docs/BOOTSTRAP.md')
    expect(diagnosticRequestId(error)).toContain('未到达服务端')
  })
})
