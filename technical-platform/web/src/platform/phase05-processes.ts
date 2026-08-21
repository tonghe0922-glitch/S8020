export type Phase05ProcessCode = 'P016' | 'P017' | 'P018' | 'P019' | 'P020'

export interface Phase05ProcessDefinition {
  code: Phase05ProcessCode
  name: string
  primaryTable: string
  states: readonly string[]
  riskGate: string
  apiBase: string
}

export const PHASE05_PROCESSES: readonly Phase05ProcessDefinition[] = [
  {
    code: 'P016',
    name: '员工福利与关怀',
    primaryTable: 'welfare.care_case',
    states: ['系统触发/员工申请/管理发起', '资格校验', '材料与隐私授权', '审批', '财务付款/实物发放/服务执行', '员工确认', '对账', '归档'],
    riskGate: '金额/预算与票据防重必须由服务端校验；财务能力不可用时禁止假执行。',
    apiBase: '/api/v1/phase05/welfare/care-cases',
  },
  {
    code: 'P017',
    name: '电子签署',
    primaryTable: 'document.signature_envelope',
    states: ['模板与数据准备', '文档生成', '内部审核', '发起签署', '身份认证', '签署', '回调验签', '文件与证书归档', '业务状态更新'],
    riskGate: '发起签署后核心文档不可变；完整签署证据不足或 Provider 不可用时禁止完成。',
    apiBase: '/api/v1/phase05/signatures/envelopes',
  },
  {
    code: 'P018',
    name: '数据导入',
    primaryTable: 'integration.data_import_job',
    states: ['下载模板', '上传文件', '格式校验', '业务校验', '权限与范围校验', '差异预览', '用户确认', '异步执行', '结果报告', '错误行下载', '审计'],
    riskGate: '差异预览确认后才允许异步执行；执行器必须唯一，失败重试三次后进入死信。',
    apiBase: '/api/v1/phase05/data-imports',
  },
  {
    code: 'P019',
    name: '敏感导出与文件下载',
    primaryTable: 'audit.data_export_request',
    states: ['发起导出/下载', '数据范围校验', '字段与敏感级别校验', '用途和审批校验', '异步生成', '水印与短时链接', '二次认证', '下载留痕', '到期销毁'],
    riskGate: '敏感下载必须审批、水印、短时链接与一次性 Step-Up 二次认证；普通登录态不能代替。',
    apiBase: '/api/v1/phase05/sensitive-exports',
  },
  {
    code: 'P020',
    name: '数据质量与修复',
    primaryTable: 'audit.data_quality_issue',
    states: ['规则扫描发现问题', '生成数据修复工单', '责任域确认权威来源', '影响分析', '修复方案审批', '双人复核执行', '下游补偿与重算', '前后值核验', '补充归档与审计'],
    riskGate: '修复方案审批人与执行人必须不同；禁止任意 SQL/script，且前后值核验完成后才能关闭。',
    apiBase: '/api/v1/phase05/data-quality/issues',
  },
] as const

export const PHASE05_CODES = PHASE05_PROCESSES.map((process) => process.code)
