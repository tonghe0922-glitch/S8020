import { describe, expect, it } from 'vitest'
import { ApiClientError, describeLoginFailure, transportError } from './api-error'

describe('login error diagnostics', () => {
  it('distinguishes credential, Redis and audit failures', () => {
    expect(describeLoginFailure(new ApiClientError('rejected', {
      kind: 'http',
      status: 401,
      code: 'authentication_rejected',
      requestId: 'req-auth',
    }))).toEqual({
      message: '租户编码、账号、密码或 MFA 验证码有误；如账号无有效任职，请联系管理员。',
      requestId: 'req-auth',
    })

    expect(describeLoginFailure(new ApiClientError('redis', {
      kind: 'http',
      status: 503,
      code: 'session_store_unavailable',
      requestId: 'req-redis',
    }))).toMatchObject({
      message: expect.stringContaining('Redis'),
      requestId: 'req-redis',
    })

    expect(describeLoginFailure(new ApiClientError('audit', {
      kind: 'http',
      status: 503,
      code: 'security_audit_unavailable',
      requestId: 'req-audit',
    }))).toMatchObject({
      message: expect.stringContaining('安全审计'),
      requestId: 'req-audit',
    })
  })

  it('always supplies a request identifier for transport failures', () => {
    const presentation = describeLoginFailure(transportError(new TypeError('offline')))
    expect(presentation.message).toContain('API')
    expect(presentation.requestId).toMatch(/^client-/)
  })
})
