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
const ENDPOINT = '/api/v1/processes/P015/points'

interface PointView {
  id: string
  businessNo: string
  workflowInstanceId: string
  workflowInstanceNo: string
  currentNodeCode: string
  status: string
  versionNo: number
  subject: string
  priority: string
  riskLevel: string
  ownerCenterId: string | null
  ownerEmployeeId: string | null
  businessDate: string | null
  factOccurredAt: string | null
  pointsDelta: number | null
  pointType: string | null
  changeAction: string | null
  reversalOfId: string | null
  currentBalance: number | null
  details: Record<string, unknown>
}

interface ActionSpec {
  code: string
  label: string
  permission: string
}

const ACTIONS: Readonly<Record<string, ActionSpec>> = {
  S02: { code: 'VALIDATE_SOURCE', label: '校验来源', permission: 'p015.points.review' },
  S03: { code: 'CHECK_DUPLICATE', label: '检查重复', permission: 'p015.points.review' },
  S04: { code: 'MATCH_RULE_VERSION', label: '匹配规则版本', permission: 'p015.points.review' },
  S05: { code: 'CALCULATE_POINTS', label: '服务端计算积分', permission: 'p015.points.review' },
  S06: { code: 'CLASSIFY_RISK', label: '完成风险分类', permission: 'p015.points.review' },
  S07: { code: 'POST_OR_REVIEW', label: '复核并不可变入账', permission: 'p015.points.review' },
  S08: { code: 'NOTIFY_EMPLOYEE', label: '通知员工', permission: 'p015.points.review' },
  S09: { code: 'ADJUST_OR_REVERSE', label: '处理调整/冲销', permission: 'p015.points.reverse' },
  S10: { code: 'RECALCULATE_BALANCE', label: '重算余额并关闭', permission: 'p015.points.reverse' },
}

const session = usePortalSessionStore()
const state = useAsyncActionState()
const rows = ref<PointView[]>([])
const createForm = reactive({
  subject: '', ownerEmployeeId: '', sourceFactKey: '', sourceType: 'INTERNAL', pointType: 'GROWTH',
  ruleCode: '', sourceEvidence: '', factOccurredAt: '', impactLevel: 'EMPLOYEE',
  contentVersion: 'P015-CONTENT-V1', periodNo: '2026-Q3', reason: '', factSummary: '',
})
const actionForm = reactive({
  summary: '', reason: '', correctionMode: 'NONE', adjustmentDelta: '', correctionReason: '', correctionEvidence: '',
})

const title = computed(() => ({
  employee: '我的成长与荣誉积分',
  center: '成长与荣誉积分复核台',
  tech: '积分规则版本与运行监控',
})[props.portal])
const description = computed(() => ({
  employee: '查看本人当前余额、来源与不可变流水。任何更正都以新的 ADJUST/REVERSAL 流水体现，原记录不会被修改。',
  center: '登记来源事件、匹配已发布规则、服务端计算积分、复核入账，并以新增流水完成调整或冲销。',
  tech: '仅查看工作流节点、版本、风险等运行元数据；不能查看员工积分明细，也不能执行积分业务动作。',
})[props.portal])
const total = computed(() => rows.value.length)
const posted = computed(() => rows.value.filter((row) => row.changeAction === 'POST').length)
const balances = computed(() => rows.value.filter((row) => row.currentBalance !== null).length)
const canCreate = computed(() => props.portal === 'center' && session.can('p015.points.create'))

function required(value: string, label: string): string {
  const normalized = value.trim()
  if (!normalized) throw new Error(`${label}不能为空`)
  return normalized
}
function optional(value: string): string | null {
  const normalized = value.trim()
  return normalized || null
}
function detail(row: PointView, key: string): string {
  const value = row.details?.[key]
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? String(value) : '-'
}
function actionFor(row: PointView): ActionSpec | undefined {
  if (props.portal !== 'center') return undefined
  const action = ACTIONS[row.currentNodeCode]
  return action && session.can(action.permission) ? action : undefined
}

async function loadRecords(): Promise<void> {
  rows.value = await session.request<PointView[]>(ENDPOINT)
}
function load(): Promise<void> { return state.run(loadRecords) }

function createCase(): Promise<void> {
  return state.run(async () => {
    const active = session.session
    if (!active?.orgId) throw new Error('缺少中心组织身份')
    const occurred = required(createForm.factOccurredAt, '事实发生时间')
    await session.request(ENDPOINT, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P015', 'create'),
      body: {
        subject: required(createForm.subject, '积分事件主题'),
        reason: required(createForm.reason, '登记原因'),
        priority: 'NORMAL', riskLevel: 'NORMAL', ownerCenterId: active.orgId,
        ownerEmployeeId: required(createForm.ownerEmployeeId, '员工 ID'),
        businessDate: new Date().toISOString().slice(0, 10),
        factOccurredAt: new Date(occurred).toISOString(),
        factSummary: required(createForm.factSummary, '事实摘要'),
        sourceFactKey: required(createForm.sourceFactKey, '来源事实唯一键'),
        sourceType: required(createForm.sourceType, '来源类型').toUpperCase(),
        pointType: required(createForm.pointType, '积分类型').toUpperCase(),
        ruleCode: required(createForm.ruleCode, '规则代码'),
        sourceEvidence: { reference: required(createForm.sourceEvidence, '来源证据引用') },
        impactLevel: required(createForm.impactLevel, '影响级别'),
        contentVersion: required(createForm.contentVersion, '内容版本'),
        periodNo: required(createForm.periodNo, '期间'),
      },
    })
    state.feedback.value = 'P015 事件已登记；积分值尚未由客户端写入，将由已发布服务端规则计算。'
    Object.assign(createForm, {
      subject: '', ownerEmployeeId: '', sourceFactKey: '', sourceType: 'INTERNAL', pointType: 'GROWTH', ruleCode: '',
      sourceEvidence: '', factOccurredAt: '', impactLevel: 'EMPLOYEE', contentVersion: 'P015-CONTENT-V1',
      periodNo: '2026-Q3', reason: '', factSummary: '',
    })
    await loadRecords()
  })
}

function execute(row: PointView): Promise<void> {
  const action = actionFor(row)
  if (!action) return Promise.resolve()
  return state.run(async () => {
    const mode = action.code === 'ADJUST_OR_REVERSE'
      ? required(actionForm.correctionMode, '更正模式').toUpperCase()
      : 'NONE'
    if (!['NONE', 'ADJUST', 'REVERSAL'].includes(mode)) throw new Error('更正模式仅允许 NONE / ADJUST / REVERSAL')
    const adjustmentDelta = mode === 'ADJUST' ? Number(required(actionForm.adjustmentDelta, '调整积分')) : null
    if (mode === 'ADJUST' && (!Number.isSafeInteger(adjustmentDelta) || adjustmentDelta === 0)) {
      throw new Error('ADJUST 必须填写非 0 整数积分')
    }
    if (mode !== 'NONE') {
      required(actionForm.correctionReason, '更正原因')
      required(actionForm.correctionEvidence, '更正证据')
    }
    await session.request(`${ENDPOINT}/${row.id}/actions/${action.code}`, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P015', action.code.toLowerCase()),
      body: {
        expectedVersion: row.versionNo,
        summary: optional(actionForm.summary), reason: optional(actionForm.reason), correctionMode: mode,
        adjustmentDelta,
        correctionReason: optional(actionForm.correctionReason),
        correctionEvidence: mode === 'NONE' ? null : { reference: required(actionForm.correctionEvidence, '更正证据') },
      },
    })
    state.feedback.value = `${row.businessNo} 已执行 ${action.label}`
    Object.assign(actionForm, { summary: '', reason: '', correctionMode: 'NONE', adjustmentDelta: '', correctionReason: '', correctionEvidence: '' })
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
      <SgjKpiCard label="流程事件" :value="total" unit="件" definition="当前数据范围内的 P015 服务端工作流" />
      <SgjKpiCard label="已不可变入账" :value="posted" unit="件" definition="已形成 POST 主流水的事件" />
      <SgjKpiCard label="已有余额快照" :value="balances" unit="件" definition="已执行服务端余额重算的事件" tone="neutral" />
    </template>

    <p v-if="state.feedback.value" class="p015-feedback" :class="{ 'p015-feedback--error': state.failed.value }">{{ state.feedback.value }}</p>

    <SgjCard v-if="canCreate" class="p015-panel">
      <template #header><h2>登记积分来源事件</h2></template>
      <div class="p015-grid">
        <SgjInput v-model="createForm.subject" label="事件主题" required />
        <SgjInput v-model="createForm.ownerEmployeeId" label="员工 ID" required />
        <SgjInput v-model="createForm.sourceFactKey" label="来源事实唯一键" required />
        <SgjInput v-model="createForm.sourceType" label="来源类型" required />
        <SgjInput v-model="createForm.pointType" label="积分类型（GROWTH/HONOR）" required />
        <SgjInput v-model="createForm.ruleCode" label="规则代码" required />
        <SgjInput v-model="createForm.sourceEvidence" label="来源证据引用" required />
        <SgjInput v-model="createForm.factOccurredAt" label="事实发生时间" type="datetime-local" required />
        <SgjInput v-model="createForm.impactLevel" label="影响级别" required />
        <SgjInput v-model="createForm.contentVersion" label="内容版本" required />
        <SgjInput v-model="createForm.periodNo" label="期间" required />
      </div>
      <SgjTextarea v-model="createForm.reason" label="登记原因" required />
      <SgjTextarea v-model="createForm.factSummary" label="来源事实摘要" required />
      <template #footer><SgjButton :loading="state.busy.value" @click="createCase">登记事件</SgjButton></template>
    </SgjCard>

    <SgjCard class="p015-panel">
      <template #header><h2>{{ portal === 'tech' ? '积分运行元数据' : '不可变积分流水与余额' }}</h2></template>
      <SgjTable :empty="rows.length === 0" :column-count="10" empty-text="暂无符合当前权限范围的 P015 记录">
        <template #head><tr><th>业务单号</th><th>节点/状态</th><th>版本</th><th>类型</th><th>积分</th><th>余额</th><th>规则版本</th><th>来源键</th><th>动作</th><th>风险</th></tr></template>
        <template #body>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ row.businessNo }}</td>
            <td><SgjStatusChip :tone="statusTone(row.status)">{{ row.status }}</SgjStatusChip><small>{{ row.currentNodeCode }}</small></td>
            <td>{{ row.versionNo }}</td><td>{{ row.pointType || '-' }}</td><td>{{ row.pointsDelta ?? '-' }}</td><td>{{ row.currentBalance ?? '-' }}</td>
            <td>{{ detail(row, 'matchedRuleVersion') }}</td><td>{{ detail(row, 'sourceFactKey') }}</td><td>{{ row.changeAction || '-' }}</td><td>{{ row.riskLevel }}</td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal === 'center'" #aside>
      <SgjCard class="p015-panel">
        <template #header><h2>当前节点处理</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要" />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />
        <SgjInput v-model="actionForm.correctionMode" label="S09 更正模式（NONE/ADJUST/REVERSAL）" />
        <SgjInput v-model="actionForm.adjustmentDelta" label="ADJUST 调整积分" type="number" />
        <SgjTextarea v-model="actionForm.correctionReason" label="更正原因" />
        <SgjInput v-model="actionForm.correctionEvidence" label="更正证据引用" />
        <div v-for="row in rows" :key="`p015-action-${row.id}`" class="p015-actions">
          <strong>{{ row.businessNo }} · {{ row.currentNodeCode }} · {{ row.status }}</strong>
          <SgjButton v-if="actionFor(row)" :loading="state.busy.value" @click="execute(row)">{{ actionFor(row)?.label }}</SgjButton>
          <small v-else>当前节点没有授予本端的合法动作。</small>
        </div>
      </SgjCard>
    </template>
  </SgjDashboardPageTemplate>
</template>

<style scoped>
.p015-panel { margin-bottom: 16px; }
.p015-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.p015-feedback { padding: 12px 14px; border: 1px solid var(--sgj-border); border-radius: 10px; }
.p015-feedback--error { font-weight: 700; }
.p015-actions { display: grid; gap: 8px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--sgj-border); }
small { display: block; margin-top: 4px; }
</style>
