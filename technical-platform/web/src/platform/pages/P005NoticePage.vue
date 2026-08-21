<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePortalSessionStore } from '../../session'
import type { PortalDefinition } from '../portal-config'

type Mode = 'employee' | 'center' | 'tech'
type ReceiptAction = 'read' | 'confirm' | 'understanding' | 'execution'
interface Notice {
  id: string
  businessNo: string
  workflowInstanceId: string | null
  workflowInstanceNo: string | null
  currentNodeCode: string | null
  status: string
  versionNo: number
  policyCode: string
  policyVersion: number
  officialSubject: string | null
  officialType: string | null
  officialContent: string | null
  periodOrCourseNo: string | null
  visibilityLevel: string
  venueChannel: string | null
  ownerCenterId: string | null
  ownerEmployeeId: string | null
  targetCenterId: string
  targetPositionCode: string | null
  understandingPassScore: number
  publishedAt: string | null
  businessDate: string | null
  effectiveStartAt: string | null
  effectiveEndAt: string | null
  executionDueAt: string | null
  archivedAt: string | null
  actualEndAt: string | null
  updatedAt: string | null
}
interface Recipient {
  id: string
  noticeId: string
  employeeId: string
  identityId: string
  orgId: string
  positionId: string
  positionCode: string | null
  deliveryStatus: string
  deliveredAt: string | null
  readAt: string | null
  confirmedAt: string | null
  understandingScore: number | null
  understandingPassedAt: string | null
  executionSummary: string | null
  executedAt: string | null
  acceptedAt: string | null
  acceptedBy: string | null
  lastRemindedAt: string | null
  escalationCount: number
  versionNo: number
  updatedAt: string | null
}
interface NoticeView {
  notice: Notice
  recipients: Recipient[]
  recipientCount: number
  deliveredCount: number
  readCount: number
  confirmedCount: number
  understandingPassedCount: number
  executedCount: number
  acceptedCount: number
}
interface PublishBody {
  policyCode: string
  officialSubject: string
  officialType: string
  officialContent: string
  periodOrCourseNo: string
  visibilityLevel: string
  venueChannel: string | null
  targetCenterId: string
  targetPositionCode: string | null
  understandingPassScore: number
  businessDate: string
  effectiveStartAt: string | null
  effectiveEndAt: string | null
  executionDueAt: string | null
}
interface ManageBody { expectedVersion: number; reason: string | null }
interface ActionSpec { code: string; label: string }

const props = defineProps<{ portal: PortalDefinition; mode: Mode }>()
const session = usePortalSessionStore()
const views = ref<NoticeView[]>([])
const busy = ref(false)
const feedback = ref('')

const policyCode = ref('POLICY')
const officialSubject = ref('')
const officialType = ref('制度通知')
const officialContent = ref('')
const periodOrCourseNo = ref('2026')
const visibilityLevel = ref('内部')
const venueChannel = ref('PC')
const targetPositionCode = ref('')
const understandingPassScore = ref(80)
const businessDate = ref(today())
const effectiveStartAt = ref('')
const effectiveEndAt = ref('')
const executionDueAt = ref('')
const understandingScore = ref(90)
const executionSummary = ref('')
const manageReason = ref('')

const isEmployee = computed(() => props.mode === 'employee')
const isCenter = computed(() => props.mode === 'center')
const isTech = computed(() => props.mode === 'tech')
const canPublish = computed(() => session.can('p005.notice.publish'))
const canRead = computed(() => session.can('p005.notice.read'))
const canReceipt = computed(() => session.can('p005.notice.receipt'))
const canManage = computed(() => session.can('p005.notice.manage'))
const canMonitor = computed(() => session.can('p005.notice.monitor'))
const canList = computed(() => canRead.value || canManage.value || canMonitor.value)
const targetCenterId = computed(() => session.session?.orgId ?? '')
const heading = computed(() => isEmployee.value ? '制度通知与执行回执' : isCenter.value ? '制度通知发布与验收' : '制度通知与执行回执监控')

const MANAGE_ACTIONS: Record<string, ActionSpec[]> = {
  S08: [{ code: 'ACCEPT_EXECUTION', label: '验收执行结果' }],
  S09: [{ code: 'RESOLVE_ESCALATIONS', label: '处理催办升级' }],
  S10: [{ code: 'ARCHIVE', label: '移交归档并关闭' }],
}
const RECEIPT_ACTIONS: Record<string, ReceiptAction> = { S04: 'read', S05: 'confirm', S06: 'understanding', S07: 'execution' }

function today(): string { return new Date().toISOString().slice(0, 10) }
function idem(prefix: string): string { return `${prefix}-${globalThis.crypto.randomUUID()}` }
function isoOrNull(value: string): string | null {
  if (!value.trim()) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('日期时间格式无效')
  return date.toISOString()
}
function integer(value: number, min: number, max: number, label: string): number {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) throw new Error(`${label}必须是 ${min}-${max} 的整数`)
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
  if (!canList.value) { views.value = []; return }
  views.value = await session.request<NoticeView[]>('/api/v1/processes/P005/notices')
}
function publish(): Promise<void> {
  return run(async () => {
    if (!canPublish.value) throw new Error('当前身份没有制度通知发布权限')
    if (!targetCenterId.value) throw new Error('当前会话缺少中心组织信息')
    const body: PublishBody = {
      policyCode: policyCode.value.trim(),
      officialSubject: officialSubject.value.trim(),
      officialType: officialType.value.trim(),
      officialContent: officialContent.value.trim(),
      periodOrCourseNo: periodOrCourseNo.value.trim(),
      visibilityLevel: visibilityLevel.value,
      venueChannel: venueChannel.value.trim() || null,
      targetCenterId: targetCenterId.value,
      targetPositionCode: targetPositionCode.value.trim() || null,
      understandingPassScore: integer(understandingPassScore.value, 0, 100, '理解验证通过分'),
      businessDate: businessDate.value,
      effectiveStartAt: isoOrNull(effectiveStartAt.value),
      effectiveEndAt: isoOrNull(effectiveEndAt.value),
      executionDueAt: isoOrNull(executionDueAt.value),
    }
    const created = await session.request<NoticeView, PublishBody>('/api/v1/processes/P005/notices', {
      method: 'POST', idempotencyKey: idem('p005-publish'), body,
    })
    feedback.value = `已发布 ${created.notice.businessNo} / ${created.notice.policyCode} V${created.notice.policyVersion}，当前节点：${created.notice.status}`
    officialSubject.value = ''; officialContent.value = ''
    await load()
  })
}
function ownRecipient(view: NoticeView): Recipient | null { return view.recipients[0] ?? null }
function receipt(view: NoticeView, kind: ReceiptAction): Promise<void> {
  return run(async () => {
    if (!canReceipt.value) throw new Error('当前身份没有制度通知回执权限')
    const recipient = ownRecipient(view)
    if (!recipient) throw new Error('当前员工不是此制度通知的服务端解析收件人')
    let body: Record<string, unknown> = { expectedRecipientVersion: recipient.versionNo }
    if (kind === 'understanding') body = { ...body, score: integer(understandingScore.value, 0, 100, '理解验证分数') }
    if (kind === 'execution') {
      const summary = executionSummary.value.trim()
      if (!summary) throw new Error('执行结果摘要不能为空')
      body = { ...body, summary }
    }
    const updated = await session.request<NoticeView, Record<string, unknown>>(
      `/api/v1/processes/P005/notices/${view.notice.id}/${kind}`, {
        method: 'POST', idempotencyKey: idem(`p005-${kind}`), body,
      })
    feedback.value = `${updated.notice.businessNo} 已提交${kind === 'read' ? '阅读' : kind === 'confirm' ? '确认' : kind === 'understanding' ? '理解验证' : '执行'}回执，当前节点：${updated.notice.status}`
    if (kind === 'execution') executionSummary.value = ''
    await load()
  })
}
function manage(view: NoticeView, actionCode: string): Promise<void> {
  return run(async () => {
    if (!canManage.value) throw new Error('当前身份没有制度通知管理权限')
    const body: ManageBody = { expectedVersion: view.notice.versionNo, reason: manageReason.value.trim() || null }
    const updated = await session.request<NoticeView, ManageBody>(
      `/api/v1/processes/P005/notices/${view.notice.id}/actions/${actionCode}`, {
        method: 'POST', idempotencyKey: idem(`p005-${actionCode.toLowerCase()}`), body,
      })
    feedback.value = `${updated.notice.businessNo} 已处理，当前节点：${updated.notice.status}`
    manageReason.value = ''
    await load()
  })
}
function manageActions(view: NoticeView): ActionSpec[] {
  if (!isCenter.value || !canManage.value || !view.notice.currentNodeCode) return []
  return MANAGE_ACTIONS[view.notice.currentNodeCode] ?? []
}
function receiptDone(recipient: Recipient, action: ReceiptAction): boolean {
  const markers: Record<ReceiptAction, string | null> = {
    read: recipient.readAt,
    confirm: recipient.confirmedAt,
    understanding: recipient.understandingPassedAt,
    execution: recipient.executedAt,
  }
  return Boolean(markers[action])
}
function receiptAction(view: NoticeView): ReceiptAction | null {
  if (!isEmployee.value || !canReceipt.value) return null
  const recipient = ownRecipient(view)
  const node = view.notice.currentNodeCode
  if (!recipient || !node) return null
  const action = RECEIPT_ACTIONS[node]
  if (!action || receiptDone(recipient, action)) return null
  return action
}
function receiptLabel(action: ReceiptAction): string {
  return action === 'read' ? '标记已阅读' : action === 'confirm' ? '确认阅签' : action === 'understanding' ? '提交理解验证' : '提交执行结果'
}
function stamp(value: string | null): string { return value ? new Date(value).toLocaleString() : '-' }
onMounted(() => { void run(load) })
</script>

<template>
  <main class="phase09-page" data-testid="p005-page">
    <header>
      <p class="phase09-kicker">PHASE-09 · P005</p>
      <h1>{{ heading }}</h1>
      <p v-if="isTech">技术端仅查看流程、版本、范围、计数和送达运行元数据；制度正文与人员回执明细不向技术监控暴露。</p>
      <p v-else>阅读不等于确认；收件范围由服务端按组织/岗位解析，流程节点只能由服务端合法迁移。</p>
    </header>

    <section v-if="isCenter" class="phase09-card">
      <h2>发布制度/通知版本</h2>
      <p v-if="!canPublish">当前身份没有 p005.notice.publish 权限。</p>
      <div class="form-grid">
        <label>制度编码<input v-model="policyCode" :disabled="busy || !canPublish" required /></label>
        <label>正式类型<input v-model="officialType" :disabled="busy || !canPublish" required /></label>
        <label>期次/课程编号<input v-model="periodOrCourseNo" :disabled="busy || !canPublish" required /></label>
        <label>可见级别
          <select v-model="visibilityLevel" :disabled="busy || !canPublish"><option>公开</option><option>内部</option><option>秘密</option><option>机密</option></select>
        </label>
        <label>目标中心<input :value="targetCenterId" disabled /></label>
        <label>目标岗位编码（可选）<input v-model="targetPositionCode" :disabled="busy || !canPublish" /></label>
        <label>理解验证通过分<input v-model.number="understandingPassScore" type="number" min="0" max="100" :disabled="busy || !canPublish" /></label>
        <label>业务日期<input v-model="businessDate" type="date" :disabled="busy || !canPublish" /></label>
        <label>发布/送达渠道<input v-model="venueChannel" :disabled="busy || !canPublish" /></label>
        <label>生效开始<input v-model="effectiveStartAt" type="datetime-local" :disabled="busy || !canPublish" /></label>
        <label>生效结束<input v-model="effectiveEndAt" type="datetime-local" :disabled="busy || !canPublish" /></label>
        <label>执行截止<input v-model="executionDueAt" type="datetime-local" :disabled="busy || !canPublish" /></label>
        <label class="wide">正式主题<input v-model="officialSubject" :disabled="busy || !canPublish" required /></label>
        <label class="wide">正式正文<textarea v-model="officialContent" :disabled="busy || !canPublish" required /></label>
      </div>
      <button type="button" :disabled="busy || !canPublish || !policyCode.trim() || !officialSubject.trim() || !officialContent.trim() || !periodOrCourseNo.trim()" @click="publish">发布制度通知</button>
    </section>

    <section v-if="isEmployee" class="phase09-card compact-controls">
      <h2>回执填写</h2>
      <label>理解验证分数<input v-model.number="understandingScore" type="number" min="0" max="100" :disabled="busy" /></label>
      <label>执行结果摘要<textarea v-model="executionSummary" :disabled="busy" placeholder="到执行任务节点后填写" /></label>
    </section>

    <section v-if="isCenter" class="phase09-card compact-controls">
      <h2>验收/归档说明</h2>
      <label>处理说明<textarea v-model="manageReason" :disabled="busy" /></label>
    </section>

    <section class="phase09-card">
      <div class="section-head">
        <h2>{{ isEmployee ? '我的制度通知' : isCenter ? '已发布制度通知' : 'P005 运行监控' }}</h2>
        <button type="button" :disabled="busy || !canList" @click="() => run(load)">刷新</button>
      </div>
      <p v-if="!canList">当前身份没有 P005 读取、管理或监控权限。</p>
      <div v-if="views.length" class="record-list">
        <article v-for="view in views" :key="view.notice.id" class="record" :data-notice-id="view.notice.id">
          <div class="record-title"><strong>{{ view.notice.businessNo }}</strong><span role="status">{{ view.notice.status }}</span></div>
          <p><strong>{{ view.notice.policyCode }} V{{ view.notice.policyVersion }}</strong> · {{ view.notice.officialType ?? '-' }} · {{ view.notice.visibilityLevel }}</p>
          <dl class="facts">
            <div><dt>流程实例</dt><dd>{{ view.notice.workflowInstanceNo ?? '-' }}</dd></div>
            <div><dt>当前节点</dt><dd>{{ view.notice.currentNodeCode ?? '-' }}</dd></div>
            <div><dt>业务版本</dt><dd>{{ view.notice.versionNo }}</dd></div>
            <div><dt>目标中心</dt><dd>{{ view.notice.targetCenterId }}</dd></div>
            <div><dt>目标岗位</dt><dd>{{ view.notice.targetPositionCode ?? '全部岗位' }}</dd></div>
            <div><dt>通过分</dt><dd>{{ view.notice.understandingPassScore }}</dd></div>
            <div><dt>发布</dt><dd>{{ stamp(view.notice.publishedAt) }}</dd></div>
            <div><dt>归档</dt><dd>{{ stamp(view.notice.archivedAt) }}</dd></div>
          </dl>
          <div class="metrics" aria-label="回执进度">
            <span>收件 {{ view.recipientCount }}</span><span>送达 {{ view.deliveredCount }}</span><span>阅读 {{ view.readCount }}</span>
            <span>确认 {{ view.confirmedCount }}</span><span>理解通过 {{ view.understandingPassedCount }}</span>
            <span>执行 {{ view.executedCount }}</span><span>验收 {{ view.acceptedCount }}</span>
          </div>
          <template v-if="!isTech">
            <h3>{{ view.notice.officialSubject }}</h3>
            <p class="content">{{ view.notice.officialContent }}</p>
            <p v-if="view.notice.periodOrCourseNo"><strong>期次/课程：</strong>{{ view.notice.periodOrCourseNo }}</p>
            <div v-if="isEmployee && ownRecipient(view)" class="receipt-facts">
              <p><strong>送达：</strong>{{ ownRecipient(view)?.deliveryStatus }} / {{ stamp(ownRecipient(view)?.deliveredAt ?? null) }}</p>
              <p><strong>阅读：</strong>{{ stamp(ownRecipient(view)?.readAt ?? null) }} · <strong>确认：</strong>{{ stamp(ownRecipient(view)?.confirmedAt ?? null) }}</p>
              <p><strong>理解分：</strong>{{ ownRecipient(view)?.understandingScore ?? '-' }} · <strong>执行：</strong>{{ stamp(ownRecipient(view)?.executedAt ?? null) }}</p>
            </div>
          </template>
          <p v-else class="metadata-note">技术监控按最小必要原则隐藏正式主题、正文、期次、渠道、发布人及全部收件人回执明细。</p>
          <div v-if="receiptAction(view)" class="actions">
            <button type="button" :disabled="busy" @click="receipt(view, receiptAction(view)!)">{{ receiptLabel(receiptAction(view)!) }}</button>
          </div>
          <div v-if="manageActions(view).length" class="actions">
            <button v-for="action in manageActions(view)" :key="action.code" type="button" :disabled="busy" @click="manage(view, action.code)">{{ action.label }}</button>
          </div>
        </article>
      </div>
      <p v-else-if="canList">当前没有可见的制度通知。</p>
    </section>
    <p v-if="feedback" role="status" class="phase09-feedback">{{ feedback }}</p>
  </main>
</template>

<style scoped>
.phase09-page{display:grid;gap:1rem;max-width:76rem;width:100%;margin:0 auto;padding:1.5rem}.phase09-kicker{font-weight:700}.phase09-card{display:grid;gap:1rem;padding:1.2rem;border:1px solid var(--sgj-border,#d8dee9);border-radius:.8rem;background:var(--sgj-surface,#fff)}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}.form-grid label,.compact-controls label{display:grid;gap:.35rem}.wide{grid-column:1/-1}input,select,textarea{min-height:2.5rem;padding:.55rem;border:1px solid #b8c0cc;border-radius:.45rem}textarea{min-height:4.5rem}.section-head,.record-title,.actions,.metrics{display:flex;gap:.7rem;justify-content:space-between;align-items:center;flex-wrap:wrap}.record-list{display:grid;gap:.8rem}.record{display:grid;gap:.65rem;padding:1rem;border:1px solid #d8dee9;border-radius:.7rem}.facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.4rem .9rem;margin:0}.facts>div{display:grid;grid-template-columns:7rem 1fr}.facts dt{font-weight:700}.facts dd{margin:0;overflow-wrap:anywhere}.metrics{justify-content:flex-start}.metrics span{padding:.25rem .5rem;border-radius:999px;background:#eef2f7}.content{white-space:pre-wrap}.metadata-note{font-size:.9rem;opacity:.75}.phase09-feedback{max-width:76rem;width:100%;margin:0 auto;padding:.8rem 1.5rem}.actions{justify-content:flex-start}@media(max-width:720px){.form-grid,.facts{grid-template-columns:1fr}.wide{grid-column:auto}.facts>div{grid-template-columns:6rem 1fr}.phase09-page{padding:1rem}}
</style>
