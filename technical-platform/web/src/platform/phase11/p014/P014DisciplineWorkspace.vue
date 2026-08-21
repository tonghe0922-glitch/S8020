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
import type { Phase11Portal, Phase11Record } from '../types'

const props = defineProps<{ portal: Phase11Portal }>()
const ENDPOINT = '/api/v1/processes/P014/discipline-cases'

interface ActionForm {
  summary: string
  reason: string
  safetyMeasure: string
  safetyEvidence: string
  investigationFinding: string
  investigationEvidence: string
  defenseStatement: string
  defenseEvidence: string
  responsibilityReview: string
  decision: string
  serviceProof: string
  impactSummary: string
  impactExecutionEvidence: string
  appealResult: string
  appealDecision: string
  appealDecisionEvidence: string
  closureSummary: string
  remediationSummary: string
  observationEvidence: string
}

type ActionField = keyof ActionForm

interface ActionSpec {
  code: string
  label: string
  permission: string
  actor: 'employee' | 'center'
  required: readonly ActionField[]
}

const session = usePortalSessionStore()
const rows = ref<Phase11Record[]>([])
const state = useAsyncActionState()
const createForm = reactive({
  subject: '',
  ownerEmployeeId: '',
  sourceFactKey: '',
  sourceType: 'INTERNAL',
  customerId: '',
  customerName: '',
  periodNo: '2026-Q3',
  contentVersion: 'P014-CONTENT-V1',
  factOccurredAt: '',
  impactEffectiveDate: '',
  impactLevel: 'EMPLOYEE',
  reason: '',
  factSummary: '',
})
const actionForm = reactive<ActionForm>({
  summary: '',
  reason: '',
  safetyMeasure: '',
  safetyEvidence: '',
  investigationFinding: '',
  investigationEvidence: '',
  defenseStatement: '',
  defenseEvidence: '',
  responsibilityReview: '',
  decision: '',
  serviceProof: '',
  impactSummary: '',
  impactExecutionEvidence: '',
  appealResult: '',
  appealDecision: '',
  appealDecisionEvidence: '',
  closureSummary: '',
  remediationSummary: '',
  observationEvidence: '',
})

const ACTIONS: Readonly<Record<string, ActionSpec>> = {
  S02: {
    code: 'APPLY_SAFETY_MEASURE', label: '完成先行止险', permission: 'p014.discipline.investigate',
    actor: 'center', required: ['summary', 'safetyMeasure', 'safetyEvidence'],
  },
  S03: {
    code: 'COMPLETE_INVESTIGATION', label: '完成调查', permission: 'p014.discipline.investigate',
    actor: 'center', required: ['summary', 'investigationFinding', 'investigationEvidence'],
  },
  S04: {
    code: 'SUBMIT_DEFENSE', label: '提交本人申辩', permission: 'p014.discipline.appeal',
    actor: 'employee', required: ['summary', 'defenseStatement', 'defenseEvidence'],
  },
  S05: {
    code: 'COMPLETE_RESPONSIBILITY_REVIEW', label: '完成责任评审', permission: 'p014.discipline.decide',
    actor: 'center', required: ['summary', 'responsibilityReview'],
  },
  S06: {
    code: 'APPROVE_DECISION', label: '批准纪律决定', permission: 'p014.discipline.decide',
    actor: 'center', required: ['summary', 'decision'],
  },
  S07: {
    code: 'ACKNOWLEDGE_SERVICE', label: '确认决定送达', permission: 'p014.discipline.appeal',
    actor: 'employee', required: ['summary', 'serviceProof'],
  },
  S08: {
    code: 'EXECUTE_IMPACTS', label: '执行纪律影响', permission: 'p014.discipline.remediate',
    actor: 'center', required: ['summary', 'impactSummary', 'impactExecutionEvidence'],
  },
  S09: {
    code: 'RESOLVE_APPEAL', label: '完成独立申诉复核', permission: 'p014.discipline.appeal',
    actor: 'center', required: ['summary', 'appealDecision', 'appealDecisionEvidence'],
  },
  S10: {
    code: 'CLOSE_CORE_CASE', label: '关闭核心案件', permission: 'p014.discipline.remediate',
    actor: 'center', required: ['summary', 'closureSummary'],
  },
  S11: {
    code: 'COMPLETE_OBSERVATION', label: '完成观察整改', permission: 'p014.discipline.remediate',
    actor: 'center', required: ['summary', 'remediationSummary', 'observationEvidence'],
  },
  S12: {
    code: 'ARCHIVE', label: '正式归档', permission: 'p014.discipline.remediate',
    actor: 'center', required: ['summary'],
  },
}

const title = computed(() => ({
  employee: '我的纪律事项与申辩',
  center: '纪律责任与申诉工作台',
  tech: '纪律流程运行与风险监控',
})[props.portal])
const description = computed(() => ({
  employee: '仅查看本人案件，并在服务端指定节点提交申辩或确认决定送达；案件状态由权威工作流决定。',
  center: '登记纪律事实、止险调查、责任评审、决定执行、独立申诉复核及观察整改；角色回避由服务端与数据库双重约束。',
  tech: '仅查看流程编号、节点、版本、风险等运行元数据，不查看敏感纪律事实，也不能执行任何业务决定。',
})[props.portal])
const total = computed(() => rows.value.length)
const open = computed(() => rows.value.filter((record) => record.currentNodeCode !== 'END').length)
const archived = computed(() => total.value - open.value)
const canCreate = computed(() => props.portal === 'center' && session.can('p014.discipline.create'))

function requireText(value: string | undefined, label: string): string {
  const normalized = value?.trim()
  if (!normalized) throw new Error(`${label}不能为空`)
  return normalized
}

function optional(value: string): string | null {
  const normalized = value.trim()
  return normalized || null
}

function evidence(value: string): Readonly<Record<string, string>> | null {
  const reference = value.trim()
  return reference ? { reference } : null
}

function detail(record: Phase11Record, key: string): string {
  const value = record.details?.[key]
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return '-'
}

function actionFor(record: Phase11Record): ActionSpec | undefined {
  if (props.portal === 'tech') return undefined
  const action = ACTIONS[record.currentNodeCode]
  if (!action || action.actor !== props.portal || !session.can(action.permission)) return undefined
  return action
}

function actionLabel(record: Phase11Record): string {
  return actionFor(record)?.label ?? ''
}

function validateAction(action: ActionSpec): void {
  for (const key of action.required) requireText(actionForm[key], key)
  if (action.code === 'RESOLVE_APPEAL' && actionForm.appealResult.trim()) {
    const result = actionForm.appealResult.trim().toUpperCase()
    if (!['UPHOLD', 'MODIFY', 'OVERTURN'].includes(result)) {
      throw new Error('申诉结果仅允许 UPHOLD / MODIFY / OVERTURN，或留空由复核结论说明')
    }
  }
}

function clearActionForm(): void {
  for (const key of Object.keys(actionForm) as ActionField[]) actionForm[key] = ''
}

async function loadRecords(): Promise<void> {
  rows.value = await session.request<Phase11Record[]>(ENDPOINT)
}

function load(): Promise<void> {
  return state.run(loadRecords)
}

function createCase(): Promise<void> {
  return state.run(async () => {
    const active = session.session
    if (!active?.orgId) throw new Error('缺少中心组织身份')
    const ownerEmployeeId = requireText(createForm.ownerEmployeeId, '被调查员工 ID')
    const factOccurredAt = requireText(createForm.factOccurredAt, '事实发生时间')
    await session.request(ENDPOINT, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P014', 'create'),
      body: {
        subject: requireText(createForm.subject, '案件主题'),
        reason: requireText(createForm.reason, '登记原因'),
        priority: 'NORMAL',
        riskLevel: 'HIGH',
        ownerCenterId: active.orgId,
        ownerEmployeeId,
        businessDate: new Date().toISOString().slice(0, 10),
        factOccurredAt: new Date(factOccurredAt).toISOString(),
        factSummary: requireText(createForm.factSummary, '事实摘要'),
        sourceFactKey: requireText(createForm.sourceFactKey, '来源事实唯一键'),
        sourceType: requireText(createForm.sourceType, '来源类型').toUpperCase(),
        customerId: optional(createForm.customerId),
        customerName: optional(createForm.customerName),
        impactLevel: requireText(createForm.impactLevel, '影响级别'),
        impactEffectiveDate: optional(createForm.impactEffectiveDate),
        contentVersion: requireText(createForm.contentVersion, '内容版本'),
        periodNo: requireText(createForm.periodNo, '期间'),
      },
    })
    state.feedback.value = 'P014 案件已登记并进入服务端 S02 先行止险节点'
    Object.assign(createForm, {
      subject: '', ownerEmployeeId: '', sourceFactKey: '', sourceType: 'INTERNAL',
      customerId: '', customerName: '', periodNo: '2026-Q3', contentVersion: 'P014-CONTENT-V1',
      factOccurredAt: '', impactEffectiveDate: '', impactLevel: 'EMPLOYEE', reason: '', factSummary: '',
    })
    await loadRecords()
  })
}

function act(record: Phase11Record, action: ActionSpec): Promise<void> {
  return state.run(async () => {
    validateAction(action)
    await session.request(`${ENDPOINT}/${record.id}/actions/${action.code}`, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P014', action.code.toLowerCase()),
      body: {
        expectedVersion: record.versionNo,
        summary: optional(actionForm.summary),
        reason: optional(actionForm.reason),
        safetyMeasure: optional(actionForm.safetyMeasure),
        safetyEvidence: evidence(actionForm.safetyEvidence),
        investigationFinding: optional(actionForm.investigationFinding),
        investigationEvidence: evidence(actionForm.investigationEvidence),
        defenseStatement: optional(actionForm.defenseStatement),
        defenseEvidence: evidence(actionForm.defenseEvidence),
        responsibilityReview: optional(actionForm.responsibilityReview),
        decision: optional(actionForm.decision),
        serviceProof: evidence(actionForm.serviceProof),
        impactSummary: optional(actionForm.impactSummary),
        impactExecutionEvidence: evidence(actionForm.impactExecutionEvidence),
        appealResult: optional(actionForm.appealResult)?.toUpperCase() ?? null,
        appealDecision: optional(actionForm.appealDecision),
        appealDecisionEvidence: evidence(actionForm.appealDecisionEvidence),
        closureSummary: optional(actionForm.closureSummary),
        remediationSummary: optional(actionForm.remediationSummary),
        observationEvidence: evidence(actionForm.observationEvidence),
      },
    })
    state.feedback.value = `${record.businessNo} 已执行 ${action.label}`
    clearActionForm()
    await loadRecords()
  })
}

function executeAvailable(record: Phase11Record): Promise<void> {
  const action = actionFor(record)
  return action ? act(record, action) : Promise.resolve()
}

onMounted(() => void load())
</script>

<template>
  <SgjDashboardPageTemplate :title="title" :description="description">
    <template #actions>
      <SgjButton variant="secondary" :loading="state.busy.value" @click="load">刷新权威事实</SgjButton>
    </template>

    <template #kpis>
      <SgjKpiCard label="案件总数" :value="total" unit="件" definition="当前数据范围内的服务端 P014 案件" />
      <SgjKpiCard label="进行中" :value="open" unit="件" definition="尚未到 END 的权威工作流" tone="danger" />
      <SgjKpiCard label="已归档" :value="archived" unit="件" definition="完成观察整改并正式归档" tone="neutral" />
    </template>

    <p v-if="state.feedback.value" class="p014-feedback" :class="{ 'p014-feedback--error': state.failed.value }">
      {{ state.feedback.value }}
    </p>

    <SgjCard v-if="canCreate" class="p014-panel">
      <template #header><h2>登记纪律线索</h2></template>
      <div class="p014-grid">
        <SgjInput v-model="createForm.subject" label="案件主题" required />
        <SgjInput v-model="createForm.ownerEmployeeId" label="被调查员工 ID" required />
        <SgjInput v-model="createForm.sourceFactKey" label="来源事实唯一键" required />
        <SgjInput v-model="createForm.sourceType" label="来源类型（INTERNAL/CUSTOMER）" required />
        <SgjInput v-model="createForm.customerId" label="CRM 客户 ID（仅客户来源可选）" />
        <SgjInput v-model="createForm.customerName" label="CRM 客户名称（仅客户来源可选）" />
        <SgjInput v-model="createForm.periodNo" label="期间" required />
        <SgjInput v-model="createForm.contentVersion" label="规则/内容版本" required />
        <SgjInput v-model="createForm.factOccurredAt" label="事实发生时间" type="datetime-local" required />
        <SgjInput v-model="createForm.impactEffectiveDate" label="影响生效日期" type="date" />
        <SgjInput v-model="createForm.impactLevel" label="影响级别" required />
      </div>
      <SgjTextarea v-model="createForm.reason" label="登记原因" required />
      <SgjTextarea v-model="createForm.factSummary" label="纪律事实摘要" required />
      <template #footer>
        <SgjButton :loading="state.busy.value" @click="createCase">登记并提交 S01</SgjButton>
      </template>
    </SgjCard>

    <SgjCard class="p014-panel">
      <template #header><h2>{{ portal === 'tech' ? '运行元数据' : '权威案件事实' }}</h2></template>
      <SgjTable :empty="rows.length === 0" :column-count="9" empty-text="暂无符合当前权限范围的 P014 案件">
        <template #head>
          <tr>
            <th>业务单号</th><th>状态/节点</th><th>版本</th><th>风险</th><th>案件主题</th>
            <th>来源事实键</th><th>调查人</th><th>决定人</th><th>申诉复核人</th>
          </tr>
        </template>
        <template #body>
          <tr v-for="record in rows" :key="record.id">
            <td>{{ record.businessNo }}</td>
            <td>
              <SgjStatusChip :tone="statusTone(record.status)">{{ record.status }}</SgjStatusChip>
              <small>{{ record.currentNodeCode }}</small>
            </td>
            <td>{{ record.versionNo }}</td>
            <td>{{ record.riskLevel }}</td>
            <td>{{ record.subject || '-' }}</td>
            <td>{{ detail(record, 'sourceFactKey') }}</td>
            <td>{{ detail(record, 'investigatorEmployeeId') }}</td>
            <td>{{ detail(record, 'decisionEmployeeId') }}</td>
            <td>{{ detail(record, 'appealReviewerEmployeeId') }}</td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal !== 'tech'" #aside>
      <SgjCard class="p014-panel">
        <template #header><h2>当前节点处理</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要（所有动作必填）" />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />

        <template v-if="portal === 'center'">
          <SgjTextarea v-model="actionForm.safetyMeasure" label="先行止险措施" />
          <SgjInput v-model="actionForm.safetyEvidence" label="止险证据引用" />
          <SgjTextarea v-model="actionForm.investigationFinding" label="调查结论" />
          <SgjInput v-model="actionForm.investigationEvidence" label="调查证据引用" />
          <SgjTextarea v-model="actionForm.responsibilityReview" label="责任评审结论" />
          <SgjTextarea v-model="actionForm.decision" label="纪律决定" />
          <SgjTextarea v-model="actionForm.impactSummary" label="影响执行摘要" />
          <SgjInput v-model="actionForm.impactExecutionEvidence" label="影响执行证据引用" />
          <SgjInput v-model="actionForm.appealResult" label="申诉结果（可选：UPHOLD/MODIFY/OVERTURN）" />
          <SgjTextarea v-model="actionForm.appealDecision" label="独立申诉复核结论" />
          <SgjInput v-model="actionForm.appealDecisionEvidence" label="申诉复核证据引用" />
          <SgjTextarea v-model="actionForm.closureSummary" label="核心案件关闭摘要" />
          <SgjTextarea v-model="actionForm.remediationSummary" label="观察整改结论" />
          <SgjInput v-model="actionForm.observationEvidence" label="观察整改证据引用" />
        </template>

        <template v-else>
          <SgjTextarea v-model="actionForm.defenseStatement" label="本人申辩陈述" />
          <SgjInput v-model="actionForm.defenseEvidence" label="申辩证据引用" />
          <SgjInput v-model="actionForm.serviceProof" label="决定送达回执/证据引用" />
        </template>

        <div v-for="record in rows" :key="`p014-action-${record.id}`" class="p014-actions">
          <strong>{{ record.businessNo }} · {{ record.currentNodeCode }} · {{ record.status }}</strong>
          <SgjButton
            v-if="actionFor(record)"
            :loading="state.busy.value"
            @click="executeAvailable(record)"
          >
            {{ actionLabel(record) }}
          </SgjButton>
          <small v-else>当前节点没有授予本端的合法动作。</small>
        </div>
      </SgjCard>
    </template>
  </SgjDashboardPageTemplate>
</template>

<style scoped>
.p014-panel { margin-bottom: 16px; }
.p014-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.p014-feedback { padding: 12px 14px; border: 1px solid var(--sgj-border); border-radius: 10px; }
.p014-feedback--error { font-weight: 700; }
.p014-actions { display: grid; gap: 8px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--sgj-border); }
small { display: block; margin-top: 4px; }
</style>
