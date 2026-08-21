import type {
  AvailableIdentityView,
  LoginBootstrapResponse,
  SessionTokenResponse,
  SessionView,
} from '../contracts'
import { protocolError } from '../api'

function recordOf(value: unknown, name: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw protocolError(`${name} 响应结构无效`)
  }
  return value as Record<string, unknown>
}

function requiredString(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  if (typeof value !== 'string' || value.length === 0) throw protocolError(`响应缺少 ${key}`)
  return value
}

function nullableString(record: Record<string, unknown>, key: string): string | null {
  const value = record[key]
  if (value === null) return null
  if (typeof value !== 'string' || value.length === 0) throw protocolError(`响应字段 ${key} 无效`)
  return value
}

function stringArray(record: Record<string, unknown>, key: string): string[] {
  const value = record[key]
  if (!Array.isArray(value)) throw protocolError(`响应字段 ${key} 不是数组`)
  return value.map((item) => {
    if (typeof item !== 'string') throw protocolError(`响应字段 ${key} 不是字符串数组`)
    return item
  })
}

function identity(value: unknown): AvailableIdentityView {
  const record = recordOf(value, 'available identity')
  const primary = record.primary
  if (typeof primary !== 'boolean') throw protocolError('响应字段 primary 无效')
  return {
    identityId: requiredString(record, 'identityId'),
    identityType: requiredString(record, 'identityType'),
    identityName: requiredString(record, 'identityName'),
    orgId: requiredString(record, 'orgId'),
    positionId: requiredString(record, 'positionId'),
    primary,
    effectiveStartAt: nullableString(record, 'effectiveStartAt'),
    effectiveEndAt: nullableString(record, 'effectiveEndAt'),
  }
}

export function parseSessionTokenResponse(value: unknown): SessionTokenResponse {
  const record = recordOf(value, 'session token')
  return {
    accessToken: requiredString(record, 'accessToken'),
    refreshToken: requiredString(record, 'refreshToken'),
    accessExpiresAt: requiredString(record, 'accessExpiresAt'),
    refreshExpiresAt: requiredString(record, 'refreshExpiresAt'),
    tenantId: requiredString(record, 'tenantId'),
    userId: requiredString(record, 'userId'),
    identityId: requiredString(record, 'identityId'),
    employeeId: requiredString(record, 'employeeId'),
    appointmentId: requiredString(record, 'appointmentId'),
    orgId: requiredString(record, 'orgId'),
    positionId: requiredString(record, 'positionId'),
  }
}

export function parseSessionView(value: unknown): SessionView {
  const record = recordOf(value, 'session')
  const candidates = record.availableIdentities
  if (!Array.isArray(candidates)) throw protocolError('响应字段 availableIdentities 无效')
  return {
    tenantId: requiredString(record, 'tenantId'),
    userId: requiredString(record, 'userId'),
    identityId: requiredString(record, 'identityId'),
    employeeId: requiredString(record, 'employeeId'),
    appointmentId: requiredString(record, 'appointmentId'),
    orgId: requiredString(record, 'orgId'),
    positionId: requiredString(record, 'positionId'),
    permissions: stringArray(record, 'permissions'),
    availableIdentities: candidates.map(identity),
  }
}

export function parseLoginBootstrapResponse(value: unknown): LoginBootstrapResponse {
  const record = recordOf(value, 'login bootstrap')
  return {
    ...parseSessionTokenResponse(record),
    session: parseSessionView(record.session),
  }
}
