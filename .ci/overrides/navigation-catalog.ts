import type { RuntimePortCode } from '../platform/portal-config'

export type NavigationIconKey =
  | 'home'
  | 'center'
  | 'contacts'
  | 'tasks'
  | 'approvals'
  | 'notices'
  | 'meetings'
  | 'learning'
  | 'shows'
  | 'expense'
  | 'services'
  | 'messages'
  | 'authz'
  | 'security'
  | 'operations'

export type CatalogMobileAccess = 'primary' | 'more'

export interface NavigationCatalogItem {
  readonly key: string
  readonly label: string
  readonly routePath?: string
}

export interface NavigationCatalogSection extends NavigationCatalogItem {
  readonly iconKey: NavigationIconKey
  readonly children?: readonly NavigationCatalogItem[]
  readonly mobileAccess: CatalogMobileAccess
  readonly centerScoped?: boolean
  readonly ports: readonly RuntimePortCode[]
}

export const WORK_NAVIGATION_CATALOG: readonly NavigationCatalogSection[] = [
  {
    key: 'home',
    label: '我的工作台',
    routePath: '/',
    iconKey: 'home',
    mobileAccess: 'primary',
    ports: ['work'],
  },
  {
    key: 'center',
    label: '中心事务',
    iconKey: 'center',
    mobileAccess: 'more',
    centerScoped: true,
    ports: ['work'],
    children: [
      { key: 'center-overview', label: '中心事务总览', routePath: '/center/02/01/01' },
      { key: 'center-meetings', label: '会议管理', routePath: '/center/06/09/03' },
      { key: 'center-attendance', label: '考勤管理', routePath: '/center/04/04/01' },
      { key: 'center-learning', label: '学习管理', routePath: '/center/06/03/07' },
    ],
  },
  {
    key: 'contacts',
    label: '企业通讯录',
    iconKey: 'contacts',
    mobileAccess: 'more',
    ports: ['work'],
  },
  {
    key: 'tasks',
    label: '待办与任务',
    iconKey: 'tasks',
    mobileAccess: 'primary',
    ports: ['work'],
    children: [
      { key: 'tasks-overview', label: '总体概况' },
      { key: 'tasks-mine', label: '我的待办' },
      { key: 'tasks-created', label: '我发起的' },
      { key: 'tasks-assign', label: '任务分配' },
    ],
  },
  {
    key: 'approvals',
    label: '审批与申请',
    iconKey: 'approvals',
    mobileAccess: 'primary',
    ports: ['work'],
    children: [
      { key: 'approvals-inbox', label: '待我审批' },
      { key: 'approvals-created', label: '我发起的审批', routePath: '/employee/03/07/04' },
      { key: 'approvals-permissions', label: '审批权限' },
    ],
  },
  {
    key: 'notices',
    label: '通知与制度',
    iconKey: 'notices',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'notices-list', label: '通知公告', routePath: '/employee/13/01/05' },
      { key: 'notices-sign', label: '待签制度' },
      { key: 'notices-receipts', label: '我的回执' },
    ],
  },
  {
    key: 'meetings',
    label: '例会与会议',
    iconKey: 'meetings',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'meetings-overview', label: '例会概况', routePath: '/employee/05/01/03' },
      { key: 'meetings-actions', label: '行动项', routePath: '/employee/05/07/02' },
      { key: 'meetings-booking', label: '会议预约' },
      { key: 'meetings-minutes', label: '会议纪要' },
    ],
  },
  {
    key: 'learning',
    label: '学习与成行',
    iconKey: 'learning',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'learning-training', label: '培训学习', routePath: '/employee/07/01/01' },
      { key: 'learning-exam', label: '在线考试', routePath: '/employee/07/04/02' },
      { key: 'learning-qualifications', label: '学历与证书', routePath: '/employee/07/06/01' },
      { key: 'learning-points', label: '成长积分', routePath: '/employee/08/06/04' },
    ],
  },
  {
    key: 'shows',
    label: '演出节目单',
    iconKey: 'shows',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'shows-program', label: '节目单' },
      { key: 'shows-mine', label: '我的场次' },
    ],
  },
  {
    key: 'expense',
    label: '报销与经费',
    iconKey: 'expense',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'expense-request', label: '报销申请', routePath: '/employee/03/07/01' },
      { key: 'expense-status', label: '状态查询' },
      { key: 'expense-ledger', label: '经费台账' },
    ],
  },
  {
    key: 'services',
    label: '个人综合服务',
    iconKey: 'services',
    mobileAccess: 'more',
    ports: ['work'],
    children: [
      { key: 'services-profile', label: '个人资料', routePath: '/employee/03/03/01' },
      { key: 'services-security', label: '账号安全', routePath: '/employee/13/04/04' },
      { key: 'services-leave', label: '请假休假', routePath: '/employee/03/01/01' },
      { key: 'services-overtime', label: '加班调休', routePath: '/employee/03/01/06' },
    ],
  },
  {
    key: 'messages',
    label: '站内通信',
    iconKey: 'messages',
    mobileAccess: 'primary',
    ports: ['work'],
  },
] as const

export const TECH_NAVIGATION_CATALOG: readonly NavigationCatalogSection[] = [
  {
    key: 'tech-home',
    label: '技术工作台',
    routePath: '/',
    iconKey: 'home',
    mobileAccess: 'primary',
    ports: ['tech'],
  },
  {
    key: 'authz',
    label: '权限与模块配置',
    iconKey: 'authz',
    mobileAccess: 'primary',
    ports: ['tech'],
    children: [
      { key: 'authz-modules', label: '模块目录', routePath: '/tech/authz/modules' },
      { key: 'authz-preview', label: '配置预览', routePath: '/tech/authz/preview' },
    ],
  },
  {
    key: 'security',
    label: '身份与安全',
    iconKey: 'security',
    mobileAccess: 'more',
    ports: ['tech'],
    children: [
      { key: 'security-sessions', label: '会话监控', routePath: '/tech/03/01/01' },
      { key: 'security-permissions', label: '权限执行', routePath: '/tech/03/01/04' },
    ],
  },
  {
    key: 'operations',
    label: '流程与运行',
    iconKey: 'operations',
    mobileAccess: 'more',
    ports: ['tech'],
    children: [
      { key: 'operations-workflow', label: '流程实例监控', routePath: '/tech/05/03/01' },
      { key: 'operations-system', label: '系统监控', routePath: '/tech/07/11/01' },
      { key: 'operations-diagnostics', label: '诊断与补偿', routePath: '/tech/07/09/01' },
    ],
  },
  {
    key: 'people-monitoring',
    label: '人员业务监控',
    iconKey: 'tasks',
    mobileAccess: 'more',
    ports: ['tech'],
    children: [
      { key: 'people-performance', label: '绩效监控', routePath: '/tech/06/05/01' },
      { key: 'people-promotion', label: '晋升监控', routePath: '/tech/01/11/07' },
      { key: 'people-reward', label: '激励监控', routePath: '/tech/06/06/01' },
      { key: 'people-points', label: '积分监控', routePath: '/tech/06/06/03' },
    ],
  },
] as const

export const NAVIGATION_CATALOG: readonly NavigationCatalogSection[] = [
  ...WORK_NAVIGATION_CATALOG,
  ...TECH_NAVIGATION_CATALOG,
]
