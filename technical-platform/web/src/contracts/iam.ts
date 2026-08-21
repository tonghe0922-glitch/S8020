export interface LoginRequest {
  tenantCode: string
  loginName: string
  password: string
  identityId?: string | null
  mfaCode?: string | null
}

export interface RefreshRequest {
  refreshToken: string
}

export interface SessionTokenResponse {
  accessToken: string
  refreshToken: string
  accessExpiresAt: string
  refreshExpiresAt: string
  tenantId: string
  userId: string
  identityId: string
  employeeId: string
  appointmentId: string
  orgId: string
  positionId: string
}

export interface LoginBootstrapResponse extends SessionTokenResponse {
  session: SessionView
}

export interface AvailableIdentityView {
  identityId: string
  identityType: string
  identityName: string
  orgId: string
  positionId: string
  primary: boolean
  effectiveStartAt: string | null
  effectiveEndAt: string | null
}

export interface SessionView {
  tenantId: string
  userId: string
  identityId: string
  employeeId: string
  appointmentId: string
  orgId: string
  positionId: string
  permissions: string[]
  availableIdentities: AvailableIdentityView[]
}

export interface SwitchRequest {
  identityId: string
}

export interface StepUpRequest {
  purpose: string
  requiredMfaLevel: number
  assertion: string
}

export interface StepUpResponse {
  ticket: string
  purpose: string
  requiredMfaLevel: number
  expiresAt: string
}

export interface ApiProblem {
  status: number
  code: string
  detail: string
  requestId: string
}
