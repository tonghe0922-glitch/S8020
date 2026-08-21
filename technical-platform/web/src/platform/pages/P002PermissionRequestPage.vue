<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePortalSessionStore } from '../../session'
import type { PortalDefinition } from '../portal-config'

type Mode = 'employee' | 'center' | 'tech'
type RequestKind = 'TEMPORARY_PERMISSION' | 'PROJECT_PERMISSION'

interface PermissionRequestRecord {
  id: string
  tenantId: string
  businessNo: string
  workflowInstanceId: string | null
  status: string
  versionNo: number
  sourceChannel: string
  businessDate: string
  subject: string
  reason: string | null
  priority: string
  riskLevel: string
  ownerCenterId: string
  ownerDepartmentId: string | null
  ownerEmployeeId: string
  plannedStartAt: string
  plannedFinishAt: string
  resultSummary: string | null
  actualStartAt: string | null
  actualEndAt: string | null
  closedAt: string | null
  targetUserId: string
  targetIdentityId: string
  requestedRoleId: string
  userRoleId: string | null
  grantStatus: string
  effectiveStartAt: string
  effectiveEndAt: string
  executedAt: string | null
  revokedAt: string | null
}

interface CreateBody {
  requestedRoleId: string
  effectiveStartAt: string
  effectiveEndAt: string
  businessObjectType: string
  businessObjectNo: string
  businessObjectName: string | null
  businessScopeId: string | null
  sourceChannel: string
  businessDate: string
  subject: string
  reason: string | null
  expectedResult: string | null
  priority: string
  externalReferenceNo: string | null
  attachments: null
}

const props = withDefaults(defineProps<{
  portal: PortalDefinition
  mode: Mode
  requestKind?: RequestKind
}>(), { requestKind: 'TEMPORARY_PERMISSION' })

const session = usePortalSessionStore()
const records = ref<PermissionRequestRecord[]>([])
const busy = ref(false)
const feedback = ref('')
const actionReason = ref('')

const requestedRoleId = ref('')
const effectiveStartAt = ref('')
const effectiveEndAt = ref('')
const businessObjectNo = ref('')
const businessObjectName = ref('')
const businessScopeId = ref('')
const subject = ref('')
const reason = ref('')
const expectedResult = ref('')
const priority = ref('NORMAL')
const externalReferenceNo = ref('')

const canRead = computed(() => session.can('p002.request.read'))
const canSubmit = computed(() => session.can('p002.request.submit'))
const canReview = computed(() => session.can('p002.request.review'))
const canExecute = computed(() => session.can('p002.request.execute'))
const canRevoke = computed(() => session.can('p002.request.revoke'))
const isEmployee = computed(() => props.mode === 'employee')
const isCenter = computed(() => props.mode === 'center')
const isTech = computed(() => props.mode === 'tech')
const heading = computed(() => {
  if (isCenter.value) return '权限申请审批收件箱'
  if (isTech.value) return '权限授权执行与回收'
  return props.requestKind === 'PROJECT_PERMISSION' ? '项目权限申请' : '临时权限申请'
})

function idem(prefix: string): string {
  return `${prefix}-${globalThis.crypto.randomUUID()}`
}

function iso(local: string): string {
  const value = new Date(local)
  if (Number.isNaN(value.getTime())) throw new Error('生效/结束时间格式无效')
  return value.toISOString()
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

async function run(action: () => Promise<void>): Promise<void> {
  busy.value = true
  feedback.value = ''
  try {
    await action()
  } catch (cause) {
    feedback.value = cause instanceof Error ? cause.message : '操作失败'
  } finally {
    busy.value = false
  }
}

async function load(): Promise<void> {
  if (!canRead.value) {
    records.value = []
    return
  }
  records.value = await session.request<PermissionRequestRecord[]>('/api/v1/processes/P002/permission-requests')
}

function submit(): Promise<void> {
  return run(async () => {
    if (!canSubmit.value) throw new Error('当前身份没有权限申请提交权限')
    const body: CreateBody = {
      requestedRoleId: requestedRoleId.value.trim(),
      effectiveStartAt: iso(effectiveStartAt.value),
      effectiveEndAt: iso(effectiveEndAt.value),
      businessObjectType: props.requestKind,
      businessObjectNo: businessObjectNo.value.trim(),
      businessObjectName: businessObjectName.value.trim() || null,
      businessScopeId: businessScopeId.value.trim() || null,
      sourceChannel: 'PORTAL',
      businessDate: today(),
      subject: subject.value.trim(),
      reason: reason.value.trim() || null,
      expectedResult: expectedResult.value.trim() || null,
      priority: priority.value,
      externalReferenceNo: externalReferenceNo.value.trim() || null,
      attachments: null,
    }
    const created = await session.request<PermissionRequestRecord, CreateBody>(
      '/api/v1/processes/P002/permission-requests',
      { method: 'POST', idempotencyKey: idem('p002-create'), body },
    )
    feedback.value = `已提交 ${created.businessNo}，当前状态：${created.status}`
    requestedRoleId.value = ''
    businessObjectNo.value = ''
    businessObjectName.value = ''
    businessScopeId.value = ''
    subject.value = ''
    reason.value = ''
    expectedResult.value = ''
    externalReferenceNo.value = ''
    await load()
  })
}

function review(record: PermissionRequestRecord, decision: 'APPROVE' | 'REJECT' | 'KEEP' | 'REVOKE'): Promise<void> {
  return run(async () => {
    const result = await session.request<PermissionRequestRecord, { expectedVersion: number; decision: string; reason: string | null }>(
      `/api/v1/processes/P002/permission-requests/${record.id}/actions/review`,
      {
        method: 'POST',
        idempotencyKey: idem('p002-review'),
        body: { expectedVersion: record.versionNo, decision, reason: actionReason.value.trim() || null },
      },
    )
    feedback.value = `${result.businessNo} 已更新为：${result.status}`
    actionReason.value = ''
    await load()
  })
}

function execute(record: PermissionRequestRecord): Promise<void> {
  return run(async () => {
    const result = await session.request<PermissionRequestRecord, { expectedVersion: number; reason: string | null }>(
      `/api/v1/processes/P002/permission-requests/${record.id}/actions/execute`,
      {
        method: 'POST',
        idempotencyKey: idem('p002-execute'),
        body: { expectedVersion: record.versionNo, reason: actionReason.value.trim() || null },
      },
    )
    feedback.value = `${result.businessNo} 已执行授权，当前状态：${result.status}`
    actionReason.value = ''
    await load()
  })
}

function revoke(record: PermissionRequestRecord): Promise<void> {
  return run(async () => {
    const result = await session.request<PermissionRequestRecord, { expectedVersion: number; reason: string | null }>(
      `/api/v1/processes/P002/permission-requests/${record.id}/actions/revoke`,
      {
        method: 'POST',
        idempotencyKey: idem('p002-revoke'),
        body: { expectedVersion: record.versionNo, reason: actionReason.value.trim() || '人工回收权限' },
      },
    )
    feedback.value = `${result.businessNo} 已完成回收，当前状态：${result.status}`
    actionReason.value = ''
    await load()
  })
}

function reviewable(record: PermissionRequestRecord): boolean {
  return ['业务负责人确认', '数据责任人复核', '高风险权限审批'].includes(record.status)
}

onMounted(() => { void run(load) })
</script>

<template>
  <main class="phase09-page" data-testid="p002-page">
    <header>
      <p class="phase09-kicker">PHASE-09 · P002</p>
      <h1>{{ heading }}</h1>
      <p>业务单、流程状态、授权与回收事实全部来自服务端 IAM / Workflow；浏览器不保存第二套业务状态。</p>
    </header>

    <section v-if="isEmployee" class="phase09-card">
      <h2>新建{{ requestKind === 'PROJECT_PERMISSION' ? '项目' : '临时' }}权限申请</h2>
      <p v-if="!canSubmit">当前身份没有 p002.request.submit 权限。</p>
      <div class="form-grid">
        <label>申请角色 ID<input v-model="requestedRoleId" :disabled="busy || !canSubmit" required /></label>
        <label>生效时间<input v-model="effectiveStartAt" type="datetime-local" :disabled="busy || !canSubmit" required /></label>
        <label>结束时间<input v-model="effectiveEndAt" type="datetime-local" :disabled="busy || !canSubmit" required /></label>
        <label>业务对象编号<input v-model="businessObjectNo" :disabled="busy || !canSubmit" required /></label>
        <label>业务对象名称<input v-model="businessObjectName" :disabled="busy || !canSubmit" /></label>
        <label>业务范围 ID<input v-model="businessScopeId" :disabled="busy || !canSubmit" /></label>
        <label class="wide">主题<input v-model="subject" :disabled="busy || !canSubmit" required /></label>
        <label class="wide">申请原因<textarea v-model="reason" :disabled="busy || !canSubmit" /></label>
        <label class="wide">期望结果<textarea v-model="expectedResult" :disabled="busy || !canSubmit" /></label>
        <label>优先级<select v-model="priority" :disabled="busy || !canSubmit"><option>NORMAL</option><option>HIGH</option></select></label>
        <label>外部引用号<input v-model="externalReferenceNo" :disabled="busy || !canSubmit" /></label>
      </div>
      <button type="button" :disabled="busy || !canSubmit || !requestedRoleId || !effectiveStartAt || !effectiveEndAt || !businessObjectNo || !subject" @click="submit">提交权限申请</button>
    </section>

    <section class="phase09-card">
      <div class="section-head">
        <h2>{{ isEmployee ? '我的权限申请' : isCenter ? '可见审批申请' : '可见授权申请' }}</h2>
        <button type="button" :disabled="busy || !canRead" @click="() => run(load)">刷新</button>
      </div>
      <p v-if="!canRead">当前身份没有 p002.request.read 权限，服务端列表不会被读取。</p>
      <label v-if="isCenter || isTech">本次操作说明<textarea v-model="actionReason" :disabled="busy" /></label>
      <div v-if="records.length" class="record-list">
        <article v-for="record in records" :key="record.id" class="record" :data-request-id="record.id">
          <div class="record-title">
            <strong>{{ record.businessNo }} · {{ record.subject }}</strong>
            <span>{{ record.status }}</span>
          </div>
          <dl>
            <div><dt>风险</dt><dd>{{ record.riskLevel }}</dd></div>
            <div><dt>版本</dt><dd>{{ record.versionNo }}</dd></div>
            <div><dt>授权状态</dt><dd>{{ record.grantStatus }}</dd></div>
            <div><dt>角色</dt><dd>{{ record.requestedRoleId }}</dd></div>
            <div><dt>有效期</dt><dd>{{ record.effectiveStartAt }} → {{ record.effectiveEndAt }}</dd></div>
            <div><dt>流程实例</dt><dd>{{ record.workflowInstanceId || '—' }}</dd></div>
          </dl>
          <div v-if="isCenter && canReview" class="phase09-actions">
            <template v-if="reviewable(record)">
              <button type="button" :disabled="busy" @click="review(record, 'APPROVE')">通过当前复核</button>
              <button type="button" :disabled="busy" @click="review(record, 'REJECT')">驳回</button>
            </template>
            <template v-else-if="record.status === '定期复核'">
              <button type="button" :disabled="busy" @click="review(record, 'KEEP')">继续保留</button>
              <button type="button" :disabled="busy" @click="review(record, 'REVOKE')">进入回收</button>
            </template>
          </div>
          <div v-if="isTech" class="phase09-actions">
            <button v-if="canExecute && record.status === '权限生效'" type="button" :disabled="busy" @click="execute(record)">执行授权</button>
            <button v-if="canRevoke && ['定期复核','到期/调岗/离职回收'].includes(record.status)" type="button" :disabled="busy" @click="revoke(record)">执行回收</button>
          </div>
        </article>
      </div>
      <p v-else-if="canRead">暂无可见权限申请。</p>
    </section>

    <p v-if="feedback" role="status">{{ feedback }}</p>
  </main>
</template>

<style scoped>
.phase09-page{display:grid;gap:1.25rem;max-width:76rem;margin:0 auto;padding:1.5rem}.phase09-kicker{font-weight:700}.phase09-card{display:grid;gap:1rem;padding:1.25rem;border:1px solid var(--sgj-border,#d8dee9);border-radius:1rem;background:var(--sgj-surface,#fff)}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}.wide{grid-column:1/-1}label{display:grid;gap:.35rem}input,textarea,select{min-height:2.75rem;padding:.65rem .8rem;border:1px solid var(--sgj-border,#cbd5e1);border-radius:.65rem;background:inherit;color:inherit}textarea{min-height:5rem;resize:vertical}.section-head,.record-title,.phase09-actions{display:flex;align-items:center;justify-content:space-between;gap:.75rem;flex-wrap:wrap}button{min-height:2.75rem;padding:.6rem 1rem;border-radius:.65rem}.record-list{display:grid;gap:.85rem}.record{display:grid;gap:.8rem;padding:1rem;border:1px solid var(--sgj-border,#e2e8f0);border-radius:.8rem}.record dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.5rem;margin:0}.record dl div{display:grid;grid-template-columns:5rem 1fr;gap:.4rem}.record dt{font-weight:700}.record dd{margin:0;overflow-wrap:anywhere}@media(max-width:720px){.form-grid,.record dl{grid-template-columns:1fr}.wide{grid-column:auto}}
</style>
