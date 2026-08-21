export type Phase11Portal = 'employee' | 'center' | 'tech'

export interface Phase11Record {
  id: string
  tenantId: string
  processCode: string
  businessNo: string
  workflowInstanceId?: string | null
  workflowInstanceNo?: string | null
  currentNodeCode: string
  status: string
  versionNo: number
  subject: string
  reason?: string | null
  priority: string
  riskLevel: string
  ownerCenterId?: string | null
  ownerEmployeeId?: string | null
  businessDate?: string | null
  factOccurredAt?: string | null
  factSummary?: string | null
  resultSummary?: string | null
  createdAt: string
  updatedAt: string
  closedAt?: string | null
  details: Record<string, unknown>
}

export interface Phase11Action {
  code: string
  label: string
  permission: string
  needsSummary?: boolean
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
}
