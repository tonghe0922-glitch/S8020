<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { usePortalSessionStore } from '../../session'
import type { PortalDefinition } from '../portal-config'

type Mode = 'employee' | 'center' | 'tech'
interface ChangeView { fieldCode: string; sensitivity: string; proposedValueMasked: string; proofProvided: boolean }
interface ProfileChangeRecord {
  id: string; businessNo: string; status: string; versionNo: number; subject: string; reason: string | null
  riskLevel: string; ownerEmployeeId: string; ownerCenterId: string; expectedEffectiveAt: string | null
  resultSummary: string | null; closedAt: string | null; changes: ChangeView[]
}
interface CreateBody {
  sourceChannel: string; businessDate: string; subject: string; reason: string | null; priority: string
  expectedEffectiveAt: string | null; knownImpact: string | null
  changes: Array<{ fieldCode: string; proposedValue: string; proofReference: string | null }>
}

const props = defineProps<{ portal: PortalDefinition; mode: Mode }>()
const session = usePortalSessionStore()
const records = ref<ProfileChangeRecord[]>([])
const busy = ref(false)
const feedback = ref('')
const actionReason = ref('')
const fieldCode = ref('person_name')
const proposedValue = ref('')
const proofReference = ref('')
const subject = ref('个人资料变更')
const reason = ref('')
const knownImpact = ref('')
const expectedEffectiveAt = ref('')

const canRead = computed(() => session.can('p003.change.read'))
const canSubmit = computed(() => session.can('p003.change.submit'))
const canReview = computed(() => session.can('p003.change.review'))
const canApply = computed(() => session.can('p003.change.apply'))
const isEmployee = computed(() => props.mode === 'employee')
const isCenter = computed(() => props.mode === 'center')
const heading = computed(() => isEmployee.value ? '个人资料变更' : isCenter.value ? '个人资料变更复核' : '个人资料权威更新与同步')

function idem(prefix: string): string { return `${prefix}-${globalThis.crypto.randomUUID()}` }
function today(): string { return new Date().toISOString().slice(0, 10) }
function isoOrNull(value: string): string | null {
  if (!value) return null
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) throw new Error('期望生效时间格式无效')
  return parsed.toISOString()
}
async function run(action: () => Promise<void>): Promise<void> {
  busy.value = true; feedback.value = ''
  try { await action() } catch (cause) { feedback.value = cause instanceof Error ? cause.message : '操作失败' }
  finally { busy.value = false }
}
async function load(): Promise<void> {
  if (!canRead.value) { records.value = []; return }
  records.value = await session.request<ProfileChangeRecord[]>('/api/v1/processes/P003/profile-changes')
}
function submit(): Promise<void> {
  return run(async () => {
    if (!canSubmit.value) throw new Error('当前身份没有个人资料变更提交权限')
    const body: CreateBody = {
      sourceChannel: 'PORTAL', businessDate: today(), subject: subject.value.trim(),
      reason: reason.value.trim() || null, priority: 'NORMAL', expectedEffectiveAt: isoOrNull(expectedEffectiveAt.value),
      knownImpact: knownImpact.value.trim() || null,
      changes: [{ fieldCode: fieldCode.value, proposedValue: proposedValue.value.trim(), proofReference: proofReference.value.trim() || null }],
    }
    const created = await session.request<ProfileChangeRecord, CreateBody>('/api/v1/processes/P003/profile-changes', {
      method: 'POST', idempotencyKey: idem('p003-create'), body,
    })
    feedback.value = `已提交 ${created.businessNo}，当前状态：${created.status}`
    proposedValue.value = ''; proofReference.value = ''; reason.value = ''; knownImpact.value = ''; expectedEffectiveAt.value = ''
    await load()
  })
}
function review(record: ProfileChangeRecord, decision: 'APPROVE' | 'REJECT'): Promise<void> {
  return run(async () => {
    const result = await session.request<ProfileChangeRecord, { expectedVersion: number; decision: string; reason: string | null }>(
      `/api/v1/processes/P003/profile-changes/${record.id}/actions/review`, {
        method: 'POST', idempotencyKey: idem('p003-review'),
        body: { expectedVersion: record.versionNo, decision, reason: actionReason.value.trim() || null },
      })
    feedback.value = `${result.businessNo} 已更新为：${result.status}`; actionReason.value = ''; await load()
  })
}
function apply(record: ProfileChangeRecord): Promise<void> {
  return run(async () => {
    const result = await session.request<ProfileChangeRecord, { expectedVersion: number; reason: string | null }>(
      `/api/v1/processes/P003/profile-changes/${record.id}/actions/apply`, {
        method: 'POST', idempotencyKey: idem('p003-apply'),
        body: { expectedVersion: record.versionNo, reason: actionReason.value.trim() || '权威主档更新并同步' },
      })
    feedback.value = `${result.businessNo} 已完成权威更新，当前状态：${result.status}`; actionReason.value = ''; await load()
  })
}
function reviewable(record: ProfileChangeRecord): boolean { return ['字段敏感级别校验', '人事/财务/归口岗核验'].includes(record.status) }
function applicable(record: ProfileChangeRecord): boolean { return record.status === '权威主档更新' }
onMounted(() => { void run(load) })
</script>

<template>
  <main class="phase09-page" data-testid="p003-page">
    <header>
      <p class="phase09-kicker">PHASE-09 · P003</p>
      <h1>{{ heading }}</h1>
      <p>变更值由服务端按字段敏感级别加密保存；列表只返回脱敏值，权威主档更新与投影同步由后端工作流执行。</p>
    </header>

    <section v-if="isEmployee" class="phase09-card">
      <h2>提交资料变更</h2>
      <p v-if="!canSubmit">当前身份没有 p003.change.submit 权限。</p>
      <div class="form-grid">
        <label>变更字段
          <select v-model="fieldCode" :disabled="busy || !canSubmit">
            <option value="person_name">姓名</option><option value="mobile">手机号</option><option value="id_no">证件号（P3，需证明）</option>
          </select>
        </label>
        <label>新值<input v-model="proposedValue" :disabled="busy || !canSubmit" autocomplete="off" required /></label>
        <label>证明引用<input v-model="proofReference" :disabled="busy || !canSubmit" placeholder="P3 字段必填" /></label>
        <label>期望生效时间<input v-model="expectedEffectiveAt" type="datetime-local" :disabled="busy || !canSubmit" /></label>
        <label class="wide">主题<input v-model="subject" :disabled="busy || !canSubmit" required /></label>
        <label class="wide">变更原因<textarea v-model="reason" :disabled="busy || !canSubmit" /></label>
        <label class="wide">已知影响<textarea v-model="knownImpact" :disabled="busy || !canSubmit" /></label>
      </div>
      <button type="button" :disabled="busy || !canSubmit || !proposedValue || !subject || (fieldCode === 'id_no' && !proofReference)" @click="submit">提交资料变更</button>
    </section>

    <section class="phase09-card">
      <div class="section-head"><h2>{{ isEmployee ? '我的变更记录' : '可见资料变更' }}</h2><button type="button" :disabled="busy || !canRead" @click="() => run(load)">刷新</button></div>
      <p v-if="!canRead">当前身份没有 p003.change.read 权限。</p>
      <label v-if="!isEmployee">本次处理说明<textarea v-model="actionReason" :disabled="busy" /></label>
      <div v-if="records.length" class="record-list">
        <article v-for="record in records" :key="record.id" class="record" :data-request-id="record.id">
          <div class="record-title"><strong>{{ record.businessNo }}</strong><span role="status">{{ record.status }}</span></div>
          <p>{{ record.subject }} · 风险 {{ record.riskLevel }}</p>
          <ul><li v-for="change in record.changes" :key="change.fieldCode"><code>{{ change.fieldCode }}</code> → {{ change.proposedValueMasked }} · {{ change.sensitivity }}<span v-if="change.proofProvided"> · 已有证明</span></li></ul>
          <div v-if="isCenter && canReview && reviewable(record)" class="actions">
            <button type="button" :disabled="busy" @click="review(record, 'APPROVE')">通过当前复核</button>
            <button type="button" :disabled="busy" @click="review(record, 'REJECT')">拒绝</button>
          </div>
          <div v-if="!isEmployee && !isCenter && canApply && applicable(record)" class="actions">
            <button type="button" :disabled="busy" @click="apply(record)">执行权威更新并同步</button>
          </div>
        </article>
      </div>
      <p v-else-if="canRead">当前没有可见的资料变更记录。</p>
    </section>
    <p v-if="feedback" role="status" class="phase09-feedback">{{ feedback }}</p>
  </main>
</template>

<style scoped>
.phase09-page{display:grid;gap:1rem;max-width:76rem;width:100%;margin:0 auto;padding:1.5rem}.phase09-kicker{font-weight:700}.phase09-card{display:grid;gap:1rem;padding:1.2rem;border:1px solid var(--sgj-border,#d8dee9);border-radius:.8rem;background:var(--sgj-surface,#fff)}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem}.form-grid label,.phase09-card>label{display:grid;gap:.35rem}.wide{grid-column:1/-1}input,select,textarea{min-height:2.5rem;padding:.55rem;border:1px solid #b8c0cc;border-radius:.45rem}textarea{min-height:4.5rem}.section-head,.record-title,.actions{display:flex;gap:.7rem;justify-content:space-between;align-items:center}.record-list{display:grid;gap:.8rem}.record{padding:1rem;border:1px solid #d8dee9;border-radius:.7rem}.actions{justify-content:flex-start}.phase09-feedback{padding:.8rem;border-radius:.6rem;background:#f3f6fa}@media(max-width:760px){.form-grid{grid-template-columns:1fr}.wide{grid-column:auto}.section-head,.record-title{align-items:flex-start;flex-direction:column}}
</style>
