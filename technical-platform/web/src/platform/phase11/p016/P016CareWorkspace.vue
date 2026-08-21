<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  SgjButton,
  SgjCard,
  SgjDashboardPageTemplate,
  SgjInput,
  SgjKpiCard,
  SgjStatusChip,
  SgjTable,
  SgjTextarea,
} from '../../../design-system'
import { usePortalSessionStore } from '../../../session'
import { idempotencyKey, statusTone, useAsyncActionState } from '../../phase10/shared'
import type { Phase11Portal } from '../types'

const props = defineProps<{ portal: Phase11Portal }>()
const ENDPOINT = '/api/v1/processes/P016/care-cases'

interface CareFactView {
  id: string
  factType: string
  summary: string
  evidenceReference: string | null
  actorEmployeeId: string
  occurredAt: string
}

interface CareCaseView {
  id: string
  processCode: string
  businessNo: string
  workflowInstanceId: string | null
  currentNodeCode: string | null
  status: string
  versionNo: number
  subject: string | null
  reason: string | null
  priority: string | null
  riskLevel: string | null
  ownerCenterId: string | null
  ownerDepartmentId: string | null
  ownerEmployeeId: string | null
  businessDate: string | null
  benefitAmount: number | null
  budgetItemId: string | null
  costCenterId: string | null
  currency: string | null
  factOccurredAt: string | null
  factSummary: string | null
  impactLevel: string | null
  resultSummary: string | null
  actualStartAt: string | null
  actualEndAt: string | null
  closedAt: string | null
  createdAt: string
  updatedAt: string
  facts: CareFactView[]
}

interface ActionSpec {
  code: string
  label: string
  permission: string
  portal: 'employee' | 'center'
  evidenceRequired: boolean
}

const ACTIONS: Readonly<Record<string, ActionSpec>> = {
  S02: { code: 'VERIFY_ELIGIBILITY', label: '完成资格核验', permission: 'p016.care.review', portal: 'center', evidenceRequired: false },
  S03: { code: 'AUTHORIZE_PRIVACY', label: '确认隐私授权', permission: 'p016.care.confirm', portal: 'employee', evidenceRequired: true },
  S04: { code: 'APPROVE_CARE', label: '审批关怀事项', permission: 'p016.care.review', portal: 'center', evidenceRequired: false },
  S05: { code: 'EXECUTE_BENEFIT', label: '执行福利', permission: 'p016.care.execute', portal: 'center', evidenceRequired: true },
  S06: { code: 'CONFIRM_RECEIPT', label: '确认收到并登记回执', permission: 'p016.care.confirm', portal: 'employee', evidenceRequired: true },
  S07: { code: 'RECONCILE', label: '完成对账', permission: 'p016.care.reconcile', portal: 'center', evidenceRequired: true },
  S08: { code: 'ARCHIVE', label: '归档关闭', permission: 'p016.care.execute', portal: 'center', evidenceRequired: false },
}

const session = usePortalSessionStore()
const state = useAsyncActionState()
const rows = ref<CareCaseView[]>([])
const createForm = reactive({
  subject: '', ownerEmployeeId: '', reason: '', factOccurredAt: '', factSummary: '',
  benefitAmount: '', budgetItemId: '', costCenterId: '', currency: 'CNY',
  impactEffectiveDate: '', impactLevel: 'EMPLOYEE', contentVersion: 'P016-CONTENT-V1', periodNo: '',
})
const actionForm = reactive({
  summary: '', reason: '', evidenceReference: '',
  invoiceCode: '', invoiceNumber: '', invoiceDate: '', invoiceAmount: '', invoiceFileId: '', invoiceSha256: '',
})

const title = computed(() => ({
  employee: '我的福利关怀与回执',
  center: '福利关怀资格、审批与执行台',
  tech: '福利事件与集成运行监控',
})[props.portal])
const description = computed(() => ({
  employee: '发起本人福利关怀申请，查看权威处理进度，并在指定节点完成隐私授权与执行回执。',
  center: '基于同一 welfare.care_case 事实主表完成资格核验、关怀审批、福利执行、对账与归档。',
  tech: '仅查看流程节点、版本、风险和运行时间等脱敏元数据，不展示福利金额、员工事实或审批内容。',
})[props.portal])
const total = computed(() => rows.value.length)
const waitingEmployee = computed(() => rows.value.filter((row) => ['S03', 'S06'].includes(row.currentNodeCode ?? '')).length)
const closed = computed(() => rows.value.filter((row) => row.status === 'CLOSED' || row.currentNodeCode === 'END').length)
const canCreate = computed(() => props.portal !== 'tech' && session.can('p016.care.create'))

function required(value: string, label: string): string {
  const normalized = value.trim()
  if (!normalized) throw new Error(`${label}不能为空`)
  return normalized
}
function optional(value: string): string | null {
  const normalized = value.trim()
  return normalized || null
}
function optionalNonNegativeNumber(value: string, label: string): number | null {
  if (!value.trim()) return null
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) throw new Error(`${label}必须为非负数字`)
  return number
}
function factLabel(type: string): string {
  return ({
    ELIGIBILITY_VERIFIED: '资格已核验', PRIVACY_AUTHORIZED: '隐私已授权', CARE_APPROVED: '关怀已审批',
    BENEFIT_EXECUTED: '福利已执行', RECEIPT_CONFIRMED: '员工已回执', RECONCILED: '已对账', ARCHIVED: '已归档',
  } as Record<string, string>)[type] ?? type
}
function actionFor(row: CareCaseView): ActionSpec | undefined {
  const node = row.currentNodeCode ?? ''
  const action = ACTIONS[node]
  if (!action || action.portal !== props.portal || !session.can(action.permission)) return undefined
  return action
}

async function loadRecords(): Promise<void> {
  rows.value = await session.request<CareCaseView[]>(ENDPOINT)
}
function load(): Promise<void> { return state.run(loadRecords) }

function createCase(): Promise<void> {
  return state.run(async () => {
    const active = session.session
    if (!active?.orgId || !active.employeeId) throw new Error('缺少当前员工组织身份')
    const ownerEmployeeId = props.portal === 'employee'
      ? active.employeeId
      : required(createForm.ownerEmployeeId, '受关怀员工 ID')
    const occurred = required(createForm.factOccurredAt, '事实发生时间')
    await session.request(ENDPOINT, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P016', 'create'),
      body: {
        subject: required(createForm.subject, '关怀事项'),
        reason: required(createForm.reason, '申请/登记原因'),
        priority: 'NORMAL', riskLevel: 'NORMAL',
        ownerCenterId: active.orgId, ownerDepartmentId: null, ownerEmployeeId,
        businessDate: new Date().toISOString().slice(0, 10),
        benefitAmount: optionalNonNegativeNumber(createForm.benefitAmount, '福利金额'),
        budgetItemId: optional(createForm.budgetItemId),
        costCenterId: required(createForm.costCenterId, '成本中心'),
        currency: required(createForm.currency, '币种').toUpperCase(),
        factOccurredAt: new Date(occurred).toISOString(),
        factSummary: required(createForm.factSummary, '来源事实摘要'),
        impactEffectiveDate: optional(createForm.impactEffectiveDate),
        impactLevel: required(createForm.impactLevel, '影响级别'),
        contentVersion: optional(createForm.contentVersion), periodNo: optional(createForm.periodNo),
      },
    })
    state.feedback.value = 'P016 关怀事项已登记，后续状态只由服务端冻结动作推进。'
    Object.assign(createForm, {
      subject: '', ownerEmployeeId: '', reason: '', factOccurredAt: '', factSummary: '', benefitAmount: '',
      budgetItemId: '', costCenterId: '', currency: 'CNY', impactEffectiveDate: '', impactLevel: 'EMPLOYEE',
      contentVersion: 'P016-CONTENT-V1', periodNo: '',
    })
    await loadRecords()
  })
}

function invoiceEvidence(action: ActionSpec): Record<string, unknown> | null {
  if (action.code !== 'EXECUTE_BENEFIT') return null
  const populated = [actionForm.invoiceCode, actionForm.invoiceNumber, actionForm.invoiceDate,
    actionForm.invoiceAmount, actionForm.invoiceFileId, actionForm.invoiceSha256].some((value) => value.trim())
  if (!populated) return null
  const amount = optionalNonNegativeNumber(required(actionForm.invoiceAmount, '发票金额'), '发票金额')
  return {
    invoiceCode: required(actionForm.invoiceCode, '发票代码'),
    invoiceNumber: required(actionForm.invoiceNumber, '发票号码'),
    invoiceDate: required(actionForm.invoiceDate, '发票日期'),
    amount,
    fileId: required(actionForm.invoiceFileId, '发票文件 ID'),
    imageSha256: required(actionForm.invoiceSha256, '发票影像 SHA256'),
  }
}

function execute(row: CareCaseView): Promise<void> {
  const action = actionFor(row)
  if (!action) return Promise.resolve()
  return state.run(async () => {
    const evidenceReference = action.evidenceRequired
      ? required(actionForm.evidenceReference, '证据/回执引用')
      : optional(actionForm.evidenceReference)
    await session.request(`${ENDPOINT}/${row.id}/actions/${action.code}`, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P016', action.code.toLowerCase()),
      body: {
        expectedVersion: row.versionNo,
        summary: required(actionForm.summary, '处理摘要'),
        reason: optional(actionForm.reason), evidenceReference,
        invoiceEvidence: invoiceEvidence(action),
      },
    })
    state.feedback.value = `${row.businessNo} 已执行 ${action.label}`
    Object.assign(actionForm, {
      summary: '', reason: '', evidenceReference: '', invoiceCode: '', invoiceNumber: '', invoiceDate: '',
      invoiceAmount: '', invoiceFileId: '', invoiceSha256: '',
    })
    await loadRecords()
  })
}

onMounted(() => void load())
</script>

<template>
  <SgjDashboardPageTemplate :title="title" :description="description">
    <template #actions>
      <SgjButton variant="secondary" :loading="state.busy.value" @click="load">刷新权威事实</SgjButton>
    </template>
    <template #kpis>
      <SgjKpiCard label="关怀事项" :value="total" unit="件" definition="当前数据权限范围内的 P016 权威关怀事项" />
      <SgjKpiCard label="等待员工确认" :value="waitingEmployee" unit="件" definition="处于隐私授权或执行回执节点的事项" />
      <SgjKpiCard label="已归档" :value="closed" unit="件" definition="资格、隐私、执行、回执与对账事实齐备后关闭的事项" tone="neutral" />
    </template>

    <p v-if="state.feedback.value" class="p016-feedback" :class="{ 'p016-feedback--error': state.failed.value }">{{ state.feedback.value }}</p>

    <SgjCard v-if="canCreate" class="p016-panel">
      <template #header><h2>{{ portal === 'employee' ? '申请本人福利关怀' : '登记福利关怀事项' }}</h2></template>
      <div class="p016-grid">
        <SgjInput v-model="createForm.subject" label="关怀事项" required />
        <SgjInput v-if="portal === 'center'" v-model="createForm.ownerEmployeeId" label="受关怀员工 ID" required />
        <SgjInput v-model="createForm.factOccurredAt" label="事实发生时间" type="datetime-local" required />
        <SgjInput v-model="createForm.benefitAmount" label="福利金额（如适用）" type="number" />
        <SgjInput v-model="createForm.budgetItemId" label="预算项目（如适用）" />
        <SgjInput v-model="createForm.costCenterId" label="成本中心" required />
        <SgjInput v-model="createForm.currency" label="币种" required />
        <SgjInput v-model="createForm.impactEffectiveDate" label="影响生效日期（如适用）" type="date" />
        <SgjInput v-model="createForm.impactLevel" label="影响级别" required />
        <SgjInput v-model="createForm.contentVersion" label="内容版本" />
        <SgjInput v-model="createForm.periodNo" label="期间（如适用）" />
      </div>
      <SgjTextarea v-model="createForm.reason" label="申请/登记原因" required />
      <SgjTextarea v-model="createForm.factSummary" label="来源事实摘要" required />
      <template #footer><SgjButton :loading="state.busy.value" @click="createCase">提交关怀事项</SgjButton></template>
    </SgjCard>

    <SgjCard class="p016-panel">
      <template #header><h2>{{ portal === 'tech' ? '福利流程运行元数据' : '福利关怀权威台账' }}</h2></template>
      <SgjTable :empty="rows.length === 0" :column-count="9" empty-text="暂无符合当前权限范围的 P016 记录">
        <template #head><tr><th>业务单号</th><th>节点/状态</th><th>版本</th><th>关怀事项</th><th>金额/币种</th><th>风险</th><th>受关怀员工</th><th>事实留痕</th><th>更新时间</th></tr></template>
        <template #body>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ row.businessNo }}</td>
            <td><SgjStatusChip :tone="statusTone(row.status)">{{ row.status }}</SgjStatusChip><small>{{ row.currentNodeCode || '-' }}</small></td>
            <td>{{ row.versionNo }}</td><td>{{ row.subject || '-' }}</td>
            <td>{{ row.benefitAmount ?? '-' }} {{ row.currency || '' }}</td><td>{{ row.riskLevel || '-' }}</td>
            <td>{{ row.ownerEmployeeId || '-' }}</td>
            <td><small v-for="fact in row.facts" :key="fact.id">{{ factLabel(fact.factType) }} · {{ fact.evidenceReference || '已留痕' }}</small><span v-if="row.facts.length === 0">-</span></td>
            <td>{{ row.updatedAt }}</td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal !== 'tech'" #aside>
      <SgjCard class="p016-panel">
        <template #header><h2>当前节点处理</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要" required />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />
        <SgjInput v-model="actionForm.evidenceReference" label="证据/隐私授权/执行/回执/对账引用" />
        <template v-if="portal === 'center'">
          <SgjInput v-model="actionForm.invoiceCode" label="执行发票代码（如有）" />
          <SgjInput v-model="actionForm.invoiceNumber" label="执行发票号码（如有）" />
          <SgjInput v-model="actionForm.invoiceDate" label="执行发票日期（如有）" type="date" />
          <SgjInput v-model="actionForm.invoiceAmount" label="执行发票金额（如有）" type="number" />
          <SgjInput v-model="actionForm.invoiceFileId" label="执行发票文件 ID（如有）" />
          <SgjInput v-model="actionForm.invoiceSha256" label="执行发票影像 SHA256（如有）" />
        </template>
        <div v-for="row in rows" :key="`p016-action-${row.id}`" class="p016-actions">
          <strong>{{ row.businessNo }} · {{ row.currentNodeCode || '-' }} · {{ row.status }}</strong>
          <SgjButton v-if="actionFor(row)" :loading="state.busy.value" @click="execute(row)">{{ actionFor(row)?.label }}</SgjButton>
          <small v-else>当前节点没有授予本端的合法动作。</small>
        </div>
      </SgjCard>
    </template>
  </SgjDashboardPageTemplate>
</template>

<style scoped>
.p016-panel { margin-bottom: 16px; }
.p016-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.p016-feedback { padding: 12px 14px; border: 1px solid var(--sgj-border); border-radius: 10px; }
.p016-feedback--error { font-weight: 700; }
.p016-actions { display: grid; gap: 8px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--sgj-border); }
small { display: block; margin-top: 4px; }
</style>
