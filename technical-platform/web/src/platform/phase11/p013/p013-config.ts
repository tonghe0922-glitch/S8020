import type { Phase11CaseBoardConfig } from '../shared/usePhase11CaseBoard'

function required(values: Readonly<Record<string, string>>, key: string, label: string): string {
  const value = values[key]?.trim()
  if (!value) throw new Error(`${label}不能为空`)
  return value
}

function integer(values: Readonly<Record<string, string>>, key: string, label: string): number {
  const value = Number(required(values, key, label))
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${label}必须是非负整数`)
  return value
}

function money(values: Readonly<Record<string, string>>, key: string, label: string): number {
  const value = Number(required(values, key, label))
  if (!Number.isFinite(value) || value < 0) throw new Error(`${label}必须是非负金额`)
  return value
}

export const P013_REWARD_CONFIG: Phase11CaseBoardConfig = {
  processCode: 'P013',
  endpoint: '/api/v1/processes/P013/rewards',
  createPermission: 'p013.reward.create',
  titles: {
    employee: '我的奖励与认可',
    center: '奖励与认可工作台',
    tech: '奖励流程运行监控',
  },
  descriptions: {
    employee: '查看本人贡献事实、审批、执行与回执进度；所有奖励影响来自服务端权威事实。',
    center: '核验证据、形成奖励建议、完成审批，并在财务与积分事实满足后执行奖励。',
    tech: '仅查看节点、版本和运行元数据，不参与奖励结论或影响执行。',
  },
  initialValues: {
    subject: '',
    ownerEmployeeId: '',
    sourceFactKey: '',
    periodNo: '2026-Q3',
    contentVersion: 'P013-CONTENT-V1',
    factOccurredAt: '',
    impactEffectiveDate: '',
    impactLevel: 'CENTER',
    pointsDelta: '0',
    benefitAmount: '0',
    compGradeImpact: '',
    reason: '',
    factSummary: '',
  },
  fields: [
    { key: 'subject', label: '奖励主题', kind: 'text', required: true },
    { key: 'ownerEmployeeId', label: '奖励对象员工 ID', kind: 'text', required: true, centerOnly: true },
    { key: 'sourceFactKey', label: '贡献事实唯一键', kind: 'text', required: true },
    { key: 'periodNo', label: '奖励周期', kind: 'text', required: true },
    { key: 'contentVersion', label: '规则/内容版本', kind: 'text', required: true },
    { key: 'factOccurredAt', label: '贡献发生时间', kind: 'datetime-local', required: true },
    { key: 'impactEffectiveDate', label: '奖励影响日期', kind: 'date' },
    { key: 'impactLevel', label: '奖励影响级别', kind: 'text', required: true },
    { key: 'pointsDelta', label: '奖励积分', kind: 'number', required: true, min: '0', step: '1' },
    { key: 'benefitAmount', label: '奖励金额', kind: 'number', required: true, min: '0', step: '0.01' },
    { key: 'compGradeImpact', label: '职级/荣誉影响', kind: 'text' },
    { key: 'reason', label: '奖励原因', kind: 'textarea', required: true },
    { key: 'factSummary', label: '贡献事实与证据摘要', kind: 'textarea', required: true },
  ],
  columns: [
    { key: 'subject', label: '主题', source: 'record' },
    { key: 'sourceFactKey', label: '事实键', source: 'details' },
    { key: 'benefitAmount', label: '金额', source: 'details' },
    { key: 'pointsDelta', label: '积分', source: 'details' },
    { key: 'pointEffectId', label: '积分影响事实', source: 'details' },
    { key: 'financeReferenceId', label: '财务事实', source: 'details' },
  ],
  actions: {
    S02: [{ code: 'VERIFY_EVIDENCE', label: '完成证据核验', permission: 'p013.reward.review', needsSummary: true }],
    S03: [{ code: 'RECOMMEND_REWARD', label: '提交奖励建议', permission: 'p013.reward.review', needsSummary: true }],
    S04: [{ code: 'APPROVE_REWARD', label: '批准奖励', permission: 'p013.reward.review', needsSummary: true }],
    S05: [{ code: 'CHECK_DUPLICATE_IMPACT', label: '校验重复影响', permission: 'p013.reward.review', needsSummary: true }],
    S06: [{ code: 'EXECUTE_REWARD', label: '执行奖励', permission: 'p013.reward.execute', needsSummary: true }],
    S07: [{ code: 'NOTIFY_EMPLOYEE', label: '完成员工告知', permission: 'p013.reward.execute', needsSummary: true }],
    S08: [{ code: 'RECORD_RECEIPTS', label: '登记执行回执', permission: 'p013.reward.execute', needsSummary: true }],
    S09: [{ code: 'ARCHIVE', label: '归档关闭', permission: 'p013.reward.execute', needsSummary: true }],
  },
  buildCreateBody(values, ownerCenterId, ownerEmployeeId) {
    const factOccurredAt = required(values, 'factOccurredAt', '贡献发生时间')
    return {
      subject: required(values, 'subject', '奖励主题'),
      reason: required(values, 'reason', '奖励原因'),
      priority: 'NORMAL',
      riskLevel: 'NORMAL',
      ownerCenterId,
      ownerEmployeeId,
      businessDate: new Date().toISOString().slice(0, 10),
      factOccurredAt: new Date(factOccurredAt).toISOString(),
      factSummary: required(values, 'factSummary', '贡献事实摘要'),
      contentVersion: required(values, 'contentVersion', '内容版本'),
      periodNo: required(values, 'periodNo', '奖励周期'),
      sourceFactKey: required(values, 'sourceFactKey', '贡献事实唯一键'),
      employeeEventType: 'P013_REWARD',
      impactLevel: required(values, 'impactLevel', '奖励影响级别'),
      impactEffectiveDate: values.impactEffectiveDate?.trim() || null,
      pointsDelta: integer(values, 'pointsDelta', '奖励积分'),
      benefitAmount: money(values, 'benefitAmount', '奖励金额'),
      compGradeImpact: values.compGradeImpact?.trim() || null,
    }
  },
}
