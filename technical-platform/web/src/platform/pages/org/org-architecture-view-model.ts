import type {
  OrgArchitectureDraftStatus,
  OrgArchitectureNode,
  OrgArchitectureNodeCommand,
} from '../../../contracts'

const TYPE_LABELS: Readonly<Record<string, string>> = {
  MANAGEMENT: '管理',
  DIRECT: '直管',
  COMPANY: '公司',
  CENTER: '中心',
  DEPARTMENT: '部门',
  GROUP: '组',
}

const STATUS_LABELS: Readonly<Record<OrgArchitectureDraftStatus, string>> = {
  DRAFT: '草稿中',
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  PUBLISHED: '已发布',
  ABANDONED: '已废弃',
}

export function orgTypeLabel(value: string): string {
  return TYPE_LABELS[value] ?? value
}

export function draftStatusLabel(value: OrgArchitectureDraftStatus): string {
  return STATUS_LABELS[value]
}

export function architectureError(cause: unknown): string {
  return cause instanceof Error ? cause.message : '组织架构请求失败'
}

export function emptyNodeCommand(parentId: string | null = null): OrgArchitectureNodeCommand {
  return {
    orgCode: '',
    orgName: '',
    orgType: parentId ? 'DEPARTMENT' : 'COMPANY',
    parentId,
    status: 'ACTIVE',
    managerEmployeeId: null,
    headcountPlan: 0,
    sortNo: 0,
    expectedVersion: 0,
    description: null,
  }
}

export function commandFromNode(node: OrgArchitectureNode): OrgArchitectureNodeCommand {
  return {
    orgCode: node.orgCode,
    orgName: node.orgName,
    orgType: node.orgType,
    parentId: node.parentId,
    status: node.status,
    managerEmployeeId: node.managerEmployeeId,
    headcountPlan: node.headcountPlan,
    sortNo: node.sortNo,
    expectedVersion: node.versionNo,
    description: node.description,
  }
}

export function cloneNodes(nodes: readonly OrgArchitectureNode[]): OrgArchitectureNode[] {
  return nodes.map((node) => ({ ...node }))
}

export function newDraftNode(command: OrgArchitectureNodeCommand): OrgArchitectureNode {
  return {
    id: globalThis.crypto.randomUUID(),
    orgCode: command.orgCode,
    orgName: command.orgName,
    orgType: command.orgType,
    parentId: command.parentId,
    path: '',
    status: command.status,
    managerEmployeeId: command.managerEmployeeId,
    memberCount: 0,
    headcountPlan: command.headcountPlan,
    sortNo: command.sortNo,
    versionNo: 0,
    description: command.description,
  }
}
