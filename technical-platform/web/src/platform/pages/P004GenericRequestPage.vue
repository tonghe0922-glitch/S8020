<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePortalSessionStore } from '../../session'
import type { PortalDefinition } from '../portal-config'

type Mode = 'employee' | 'center' | 'tech'
interface GenericRequestRecord {
  id: string
  businessNo: string
  workflowInstanceId: string | null
  workflowInstanceNo: string | null
  currentNodeCode: string | null
  status: string
  versionNo: number
  requestType: string
  subject: string
  reason: string | null
  requestedResult: string | null
  businessDate: string
  actualAmount: number | null
  actualEndAt: string | null
  ownerCenterId: string | null
  ownerEmployeeId: string | null
  priority: string | null
  riskLevel: string | null
  amount: number | null
  initialSubmissionId: string | null
  initialSubmissionNo: string | null
  initialFormVersion: number
  resultSummary: string | null
  updatedAt: string | null
}
interface CreateBody {
  requestType: string
  subject: string
  reason: string | null
  requestedResult: string | null
  businessDate: string
  priority: string
  riskLevel: string
  amount: number | null
}
interface ActionBody {
  expectedVersion: number
  reason: string | null
  resultSummary: string | null
  actualAmount: number | null
}
interface ActionSpec { code: string; label: string; destructive?: boolean }

const props = defineProps<{ portal: PortalDefinition; mode: Mode }>()
const session = usePortalSessionStore()
const records = ref<GenericRequestRecord[]>([])
const busy = ref(false)
const feedback = ref('')
const requestType = ref('GENERAL')
const subject = ref('')
const reason = ref('')
const requestedResult = ref('')
const businessDate = ref(today())
const priority = ref('NORMAL')
const riskLevel = ref('NORMAL')
const amount = ref('')
const actionReason = ref('')
const resultSummary = ref('')
const actualAmount = ref('')

const isEmployee = computed(() => props.mode === 'employee')
const isCenter = computed(() => props.mode === 'center')
const isTech = computed(() => props.mode === 'tech')
const canRead = computed(() => session.can('p004.request.read'))
const canSubmit = computed(() => session.can('p004.request.submit'))
const canAct = computed(() => session.can('p004.request.act'))
const heading = computed(() => isEmployee.value ? '通用申请与审批' : isCenter.value ? '通用申请审批与执行' : '通用流程实例监控')

const ACTIONS: Record<string, ActionSpec[]> = {
  S03: [
    { code: 'ACCEPT', label: '受理并进入提交审批' },
    { code: 'RETURN', label: '退回补充' },
    { code: 'REJECT', label: '拒绝并关闭', destructive: true },
  ],
  S04: [
    { code: 'SUBMIT_APPROVAL', label: '提交审批处理' },
    { code: 'RETURN', label: '退回补充' },
    { code: 'REJECT', label: '拒绝并关闭', destructive: true },
  ],
  S05: [
    { code: 'APPROVE', label: '批准' },
    { code: 'RETURN', label: '退回补充' },
    { code: 'REJECT', label: '拒绝并关闭', destructive: true },
  ],
  S06: [{ code: 'CREATE_TASK', label: '生成执行任务' }],
  S07: [{ code: 'SUBMIT_RESULT', label: '提交执行结果' }],
  S08: [
    { code: 'ACCEPT_RESULT', label: '验收通过' },
    { code: 'RETURN', label: '退回重新执行' },
  ],
  S09: [
    { code: 'COMPLETE', label: '完成异常补偿' },
    { code: 'RETRY', label: '补偿后重新执行' },
  ],
  S10: [{ code: 'ARCHIVE', label: '归档关闭' }],
}

function today(): string { return new Date().toISOString().slice(0, 10) }
function idem(prefix: string): string { return `${prefix}-${globalThis.crypto.randomUUID()}` }
function nullableNumber(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const normalized = typeof value === 'number' ? value : value.trim()
  if (normalized === '') return null
  const parsed = Number(normalized)
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error('金额必须是大于或等于 0 的有效数字')
  return parsed
}
async function run(action: () => Promise<void>): Promise<void> {
  busy.value = true
  feedback.value = ''
  try { await action() }
  catch (cause) { feedback.value = cause instanceof Error ? cause.message : '操作失败' }
  finally { busy.value = false }
}
async function load(): Promise<void> {
  if (!canRead.value) { records.value = []; return }
  records.value = await session.request<GenericRequestRecord[]>('/api/v1/processes/P004/generic-requests')
}
function submit(): Promise<void> {
  return run(async () => {
    if (!canSubmit.value) throw new Error('当前身份没有通用申请提交权限')
    const body: CreateBody = {
      requestType: requestType.value.trim(), subject: subject.value.trim(), reason: reason.value.trim() || null,
      requestedResult: requestedResult.value.trim() || null, businessDate: businessDate.value,
      priority: priority.value, riskLevel: riskLevel.value, amount: nullableNumber(amount.value),
    }
    const created = await session.request<GenericRequestRecord, CreateBody>('/api/v1/processes/P004/generic-requests', {
      method: 'POST', idempotencyKey: idem('p004-create'), body,
    })
    feedback.value = `已提交 ${created.businessNo}，流程实例 ${created.workflowInstanceNo ?? '-'}，当前节点：${created.status}`
    subject.value = ''; reason.value = ''; requestedResult.value = ''; amount.value = ''
    await load()
  })
}
function perform(record: GenericRequestRecord, action: string): Promise<void> {
  return run(async () => {
    const applicantAction = record.currentNodeCode === 'S02' && ['SUBMIT', 'WITHDRAW'].includes(action)
    if (applicantAction && !canSubmit.value) throw new Error('当前身份没有重新提交/撤回权限')
    if (!applicantAction && !canAct.value) throw new Error('当前身份没有通用申请处理权限')
    const body: ActionBody = {
      expectedVersion: record.versionNo,
      reason: actionReason.value.trim() || null,
      resultSummary: resultSummary.value.trim() || null,
      actualAmount: action === 'SUBMIT_RESULT' ? nullableNumber(actualAmount.value) : null,
    }
    const updated = await session.request<GenericRequestRecord, ActionBody>(
      `/api/v1/processes/P004/generic-requests/${record.id}/actions/${action}`, {
        method: 'POST', idempotencyKey: idem(`p004-${action.toLowerCase()}`), body,
      })
    feedback.value = `${updated.businessNo} 已处理，当前节点：${updated.status}`
    actionReason.value = ''; resultSummary.value = ''; actualAmount.value = ''
    await load()
  })
}
function actionsFor(record: GenericRequestRecord): ActionSpec[] {
  if (!isCenter.value || !canAct.value || !record.currentNodeCode) return []
  return ACTIONS[record.currentNodeCode] ?? []
}
function employeeActions(record: GenericRequestRecord): ActionSpec[] {
  if (!isEmployee.value || !canSubmit.value || record.currentNodeCode !== 'S02') return []
  return [
    { code: 'SUBMIT', label: '重新提交' },
    { code: 'WITHDRAW', label: '撤回并关闭', destructive: true },
  ]
}
function money(value: number | null): string { return value === null ? '-' : Number(value).toFixed(2) }
onMounted(() => { void run(load) })
</script>

<template>
  <main class="phase09-page" data-testid="p004-page">
    <header>
      <p class="phase09-kicker">PHASE-09 · P004</p>
      <h1>{{ heading }}</h1>
      <p v-if="isTech">技术端仅查看流程实例、节点和版本等运行元数据，不拥有业务审批权。</p>
      <p v-else>流程状态只能由服务端工作流合法迁移；申请人不得自批，关键复核与执行/验收按人员分离规则处理。</p>
    </header>

    <section v-if="isEmployee" class="phase09-card">
      <h2>发起通用申请</h2>
      <p v-if="!canSubmit">当前身份没有 p004.request.submit 权限。</p>
      <div class="form-grid">
        <label>业务事项类型<input v-model="requestType" :disabled="busy || !canSubmit" required /></label>
        <label>业务日期<input v-model="businessDate" type="date" :disabled="busy || !canSubmit" required /></label>
        <label>优先级
          <select v-model="priority" :disabled="busy || !canSubmit"><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select>
        </label>
        <label>风险级别
          <select v-model="riskLevel" :disabled="busy || !canSubmit"><option value="NORMAL">普通</option><option value="MEDIUM">中</option><option value="HIGH">高</option></select>
        </label>
        <label>申请金额（可选）<input v-model="amount" type="number" min="0" step="0.01" :disabled="busy || !canSubmit" /></label>
        <label class="wide">主题<input v-model="subject" :disabled="busy || !canSubmit" required /></label>
        <label class="wide">申请原因<textarea v-model="reason" :disabled="busy || !canSubmit" /></label>
        <label class="wide">期望结果<textarea v-model="requestedResult" :disabled="busy || !canSubmit" /></label>
      </div>
      <button type="button" :disabled="busy || !canSubmit || !requestType.trim() || !subject.trim() || !businessDate" @click="submit">提交申请</button>
    </section>

    <section v-if="isCenter" class="phase09-card">
      <h2>本次节点处理信息</h2>
      <div class="form-grid">
        <label class="wide">处理说明<textarea v-model="actionReason" :disabled="busy" /></label>
        <label class="wide">结果摘要<textarea v-model="resultSummary" :disabled="busy" placeholder="执行、验收、补偿或归档时按实际需要填写" /></label>
        <label>实际金额（执行结果可填）<input v-model="actualAmount" type="number" min="0" step="0.01" :disabled="busy" /></label>
      </div>
    </section>

    <section class="phase09-card">
      <div class="section-head">
        <h2>{{ isEmployee ? '我的申请记录' : isCenter ? '可处理申请' : '流程实例监控' }}</h2>
        <button type="button" :disabled="busy || !canRead" @click="() => run(load)">刷新</button>
      </div>
      <p v-if="!canRead">当前身份没有 p004.request.read 权限。</p>
      <div v-if="records.length" class="record-list">
        <article v-for="record in records" :key="record.id" class="record" :data-request-id="record.id">
          <div class="record-title"><strong>{{ record.businessNo }}</strong><span role="status">{{ record.status }}</span></div>
          <p><strong>{{ record.subject }}</strong> · {{ record.requestType }} · {{ record.businessDate }}</p>
          <dl class="facts">
            <div><dt>实例号</dt><dd>{{ record.workflowInstanceNo ?? '-' }}</dd></div>
            <div><dt>当前节点</dt><dd>{{ record.currentNodeCode ?? '-' }}</dd></div>
            <div><dt>版本</dt><dd>{{ record.versionNo }}</dd></div>
            <div><dt>初始提交号</dt><dd>{{ record.initialSubmissionNo ?? '-' }}</dd></div>
            <div><dt>初始表单版本</dt><dd>{{ record.initialFormVersion || '-' }}</dd></div>
            <div><dt>优先级 / 风险</dt><dd>{{ record.priority ?? '-' }} / {{ record.riskLevel ?? '-' }}</dd></div>
            <div><dt>申请金额</dt><dd>{{ money(record.amount) }}</dd></div>
            <div><dt>实际金额</dt><dd>{{ money(record.actualAmount) }}</dd></div>
          </dl>
          <template v-if="!isTech">
            <p v-if="record.reason"><strong>申请原因：</strong>{{ record.reason }}</p>
            <p v-if="record.requestedResult"><strong>期望结果：</strong>{{ record.requestedResult }}</p>
            <p v-if="record.resultSummary"><strong>结果摘要：</strong>{{ record.resultSummary }}</p>
          </template>
          <p v-else class="metadata-note">业务自由文本按技术端最小必要原则隐藏，仅显示运行元数据。</p>
          <div v-if="employeeActions(record).length" class="actions">
            <button v-for="action in employeeActions(record)" :key="action.code" type="button"
              :class="{ danger: action.destructive }" :disabled="busy" @click="perform(record, action.code)">{{ action.label }}</button>
          </div>
          <div v-if="actionsFor(record).length" class="actions">
            <button v-for="action in actionsFor(record)" :key="action.code" type="button"
              :class="{ danger: action.destructive }" :disabled="busy" @click="perform(record, action.code)">{{ action.label }}</button>
          </div>
        </article>
      </div>
      <p v-else-if="canRead">当前没有可见的通用申请记录。</p>
    </section>
    <p v-if="feedback" role="status" class="phase09-feedback">{{ feedback }}</p>
  </main>
</template>

<style scoped>
.phase09-page{display:grid;gap:1rem;max-width:76rem;width:100%;margin:0 auto;padding:1.5rem}.phase09-kicker{font-weight:700}.phase09-card{display:grid;gap:1rem;padding:1.2rem;border:1px solid var(--sgj-border,#d8dee9);border-radius:.8rem;background:var(--sgj-surface,#fff)}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}.form-grid label{display:grid;gap:.35rem}.wide{grid-column:1/-1}input,select,textarea{min-height:2.5rem;padding:.55rem;border:1px solid #b8c0cc;border-radius:.45rem}textarea{min-height:4.5rem}.section-head,.record-title,.actions{display:flex;gap:.7rem;justify-content:space-between;align-items:center}.record-list{display:grid;gap:.8rem}.record{display:grid;gap:.65rem;padding:1rem;border:1px solid #d8dee9;border-radius:.7rem}.facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.4rem .9rem;margin:0}.facts>div{display:grid;grid-template-columns:7rem 1fr;gap:.4rem}.facts dt{font-weight:600}.facts dd{margin:0;overflow-wrap:anywhere}.actions{justify-content:flex-start;flex-wrap:wrap}.danger{border-color:#b42318}.metadata-note{font-size:.95rem;opacity:.75}.phase09-feedback{padding:.8rem;border-radius:.6rem;background:#f3f6fa}@media(max-width:760px){.form-grid,.facts{grid-template-columns:1fr}.wide{grid-column:auto}.section-head,.record-title{align-items:flex-start;flex-direction:column}.facts>div{grid-template-columns:6rem 1fr}}
</style>