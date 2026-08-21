export type RuntimePortCode = 'work' | 'tech'
export type BusinessAudience = 'self' | 'center' | 'tech'

export interface PortalDefinition {
  readonly code: RuntimePortCode
  readonly entry: 'work' | 'tech'
  readonly title: string
  readonly description: string
  readonly homeTitle: string
  readonly homeFocus: readonly string[]
}

const WORK: PortalDefinition = {
  code: 'work',
  entry: 'work',
  title: '工作端',
  description: '统一承载员工本人业务和中心管理业务；页面、动作与数据范围由当前身份、岗位、组织和服务端权限决定。',
  homeTitle: '我的工作台',
  homeFocus: [
    '员工本人操作和中心管理操作在同一工作端完成，不再拆分成独立端口。',
    '一人多岗通过站内身份切换重新获取权限和数据范围，不叠加旧身份授权。',
    '未完成模块保留清晰的开发中状态，不使用静态假数据冒充真实业务。',
  ],
}

const TECH: PortalDefinition = {
  code: 'tech',
  entry: 'tech',
  title: '技术端',
  description: '用于技术配置、权限分配、流程参数、运行监控、重试补偿和审计，不代表业务超级管理员。',
  homeTitle: '技术运行工作入口',
  homeFocus: [
    '模块、权限、流程、表单、规则与参数配置由本端按授权维护。',
    '服务健康、异步任务、外部集成、审计和故障处置按技术权限开放。',
    '技术端不替代业务审批，也不能绕过服务端状态机直接完成业务。',
  ],
}

export const PORTALS = {
  work: WORK,
  tech: TECH,
} as const satisfies Readonly<Record<RuntimePortCode, PortalDefinition>>
