export type OrgArchitectureDraftStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'PUBLISHED'
  | 'ABANDONED'

export type OrgArchitectureChangeKind = 'BASELINE' | 'ADDED' | 'UPDATED' | 'REMOVED'

export interface OrgArchitectureNode {
  id: string
  orgCode: string
  orgName: string
  orgType: string
  parentId: string | null
  path: string
  status: string
  managerEmployeeId: string | null
  memberCount: number
  headcountPlan: number
  sortNo: number
  versionNo: number
  description: string | null
}

export interface OrgArchitectureNodeCommand {
  orgCode: string
  orgName: string
  orgType: string
  parentId: string | null
  status: string
  managerEmployeeId: string | null
  headcountPlan: number
  sortNo: number
  expectedVersion: number
  description: string | null
}

export interface OrgArchitectureToggleCommand {
  active: boolean
  cascade: boolean
}

export interface OrgArchitectureChangeLine {
  kind: OrgArchitectureChangeKind
  nodeId: string | null
  orgCode: string | null
  summary: string
}

export interface OrgArchitectureDraft {
  id: string
  title: string
  status: OrgArchitectureDraftStatus
  baseVersion: number
  versionNo: number
  snapshot: OrgArchitectureNode[]
  changes: OrgArchitectureChangeLine[]
  createdBy: string
  createdAt: string
  updatedAt: string
  submittedBy: string | null
  submittedAt: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  reviewComment: string | null
  publishedBy: string | null
  publishedAt: string | null
  publishKey: string | null
}

export interface OrgArchitectureDirectoryMember {
  employeeId: string
  employeeNo: string
  displayName: string
  orgId: string
  orgName: string
  positionId: string
  positionName: string
}

export interface OrgArchitectureDirectory {
  versionNo: number
  publishedAt: string | null
  organizations: OrgArchitectureNode[]
  members: OrgArchitectureDirectoryMember[]
}
