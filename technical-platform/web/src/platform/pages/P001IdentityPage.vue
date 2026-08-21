<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { BusinessAudience, PortalDefinition } from '../portal-config'
import { usePortalSessionStore } from '../../session'

interface Enrollment {
  credentialId: string
  versionNo: number
  secret: string
  otpauthUri: string
  status: string
}

interface CredentialResponse {
  id: string
  status: string
  versionNo: number
  confirmedAt: string | null
  disabledAt: string | null
}

interface MfaStatus {
  configured: boolean
  status: string
  versionNo: number
  confirmedAt: string | null
  disabledAt: string | null
}

interface SessionSummary {
  familyId: string
  identityId: string
  employeeId: string
  orgId: string
  positionId: string
  issuedAt: string
  accessExpiresAt: string
  refreshExpiresAt: string
}

const props = defineProps<{ portal: PortalDefinition; audience: BusinessAudience }>()
const session = usePortalSessionStore()
const enrollment = ref<Enrollment | null>(null)
const mfaStatus = ref<MfaStatus | null>(null)
const sessions = ref<SessionSummary[]>([])
const issuer = ref('上金谷')
const accountName = ref(session.session?.userId ?? '')
const currentPassword = ref('')
const code = ref('')
const expectedVersion = ref(0)
const targetUserId = ref('')
const busy = ref(false)
const feedback = ref('')

const isTech = computed(() => props.audience === 'tech')
const isCenter = computed(() => props.audience === 'center')
const isMonitorPortal = computed(() => props.audience !== 'self')
const canMonitor = computed(() => session.can('p001.session.monitor'))
const mfaActive = computed(() => mfaStatus.value?.status === 'ACTIVE')
const title = computed(() => {
  if (isTech.value) return '身份与会话安全监控'
  if (isCenter.value) return '身份与会话审批监督'
  return '账号安全与会话管理'
})

function key(prefix: string): string {
  return `${prefix}-${globalThis.crypto.randomUUID()}`
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

async function fetchMfaStatus(): Promise<void> {
  const value = await session.request<MfaStatus>('/api/v1/processes/P001/mfa/totp')
  mfaStatus.value = value
  expectedVersion.value = value.versionNo
}

async function fetchSessions(): Promise<void> {
  const query = isMonitorPortal.value && targetUserId.value ? `?userId=${encodeURIComponent(targetUserId.value)}` : ''
  sessions.value = await session.request<SessionSummary[]>(`/api/v1/processes/P001/sessions${query}`)
}

function enroll(): Promise<void> {
  return run(async () => {
    try {
      const value = await session.request<Enrollment, { issuer: string; accountName: string; password: string }>(
        '/api/v1/processes/P001/mfa/totp/enroll',
        {
          method: 'POST',
          idempotencyKey: key('p001-enroll'),
          body: { issuer: issuer.value, accountName: accountName.value, password: currentPassword.value },
        },
      )
      enrollment.value = value
      expectedVersion.value = value.versionNo
      mfaStatus.value = { configured: true, status: value.status, versionNo: value.versionNo, confirmedAt: null, disabledAt: null }
      feedback.value = '身份已重新验证。TOTP 已创建，请在验证器中添加后完成确认。'
    } finally {
      currentPassword.value = ''
    }
  })
}

function confirm(): Promise<void> {
  return run(async () => {
    const value = await session.request<CredentialResponse, { expectedVersion: number; code: string }>(
      '/api/v1/processes/P001/mfa/totp/confirm',
      {
        method: 'POST',
        idempotencyKey: key('p001-confirm'),
        body: { expectedVersion: expectedVersion.value, code: code.value },
      },
    )
    expectedVersion.value = value.versionNo
    mfaStatus.value = {
      configured: true,
      status: value.status,
      versionNo: value.versionNo,
      confirmedAt: value.confirmedAt,
      disabledAt: value.disabledAt,
    }
    enrollment.value = null
    code.value = ''
    feedback.value = 'MFA 已启用。'
  })
}

function disable(): Promise<void> {
  return run(async () => {
    await session.request('/api/v1/processes/P001/mfa/totp', {
      method: 'DELETE',
      idempotencyKey: key('p001-disable'),
      body: { expectedVersion: expectedVersion.value, code: code.value },
    })
    code.value = ''
    enrollment.value = null
    await fetchMfaStatus()
    feedback.value = 'MFA 已停用。'
  })
}

function loadSessions(): Promise<void> {
  return run(fetchSessions)
}

onMounted(() => {
  void run(async () => {
    if (!isMonitorPortal.value) await fetchMfaStatus()
    await fetchSessions()
  })
})
</script>

<template>
  <main class="phase09-page">
    <header>
      <p class="phase09-kicker">PHASE-09 · P001</p>
      <h1>{{ title }}</h1>
      <p>所有身份、会话与 MFA 事实来自服务端 IAM；本页不在浏览器保存第二套认证事实。</p>
    </header>

    <section v-if="!isMonitorPortal" class="phase09-card">
      <h2>TOTP 多因素认证</h2>
      <p v-if="mfaStatus" data-testid="p001-mfa-status">
        服务端状态：<strong>{{ mfaStatus.status }}</strong> · 版本 {{ mfaStatus.versionNo }}
      </p>
      <label>发行方<input v-model="issuer" :disabled="busy" /></label>
      <label>账号标识<input v-model="accountName" :disabled="busy" /></label>
      <label>当前密码（绑定前重新验证）<input v-model="currentPassword" type="password" autocomplete="current-password" :disabled="busy" /></label>
      <button type="button" :disabled="busy || !issuer || !accountName || !currentPassword || mfaActive" @click="enroll">
        {{ mfaStatus?.status === 'PENDING' ? '重新创建 TOTP' : '创建 TOTP' }}
      </button>
      <div v-if="enrollment" class="phase09-secret">
        <strong>一次性绑定密钥</strong>
        <code>{{ enrollment.secret }}</code>
        <small>仅用于本次绑定，请添加到验证器后立即确认。</small>
      </div>
      <label>6 位验证码<input v-model="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="busy" /></label>
      <label>当前版本<input v-model.number="expectedVersion" type="number" min="0" disabled /></label>
      <div class="phase09-actions">
        <button type="button" :disabled="busy || code.length !== 6 || mfaStatus?.status !== 'PENDING'" @click="confirm">确认启用</button>
        <button type="button" :disabled="busy || code.length !== 6 || !mfaActive" @click="disable">停用 MFA</button>
      </div>
    </section>

    <section class="phase09-card">
      <h2>活动会话</h2>
      <label v-if="isMonitorPortal && canMonitor">目标用户 ID<input v-model="targetUserId" :disabled="busy" placeholder="UUID；留空查看本人" /></label>
      <button type="button" :disabled="busy || (isMonitorPortal && !canMonitor)" @click="loadSessions">刷新服务端会话</button>
      <p v-if="isMonitorPortal && !canMonitor">当前身份没有 p001.session.monitor 权限。</p>
      <table v-if="sessions.length">
        <thead><tr><th>身份</th><th>员工</th><th>组织</th><th>访问到期</th></tr></thead>
        <tbody>
          <tr v-for="item in sessions" :key="item.familyId">
            <td>{{ item.identityId }}</td><td>{{ item.employeeId }}</td><td>{{ item.orgId }}</td><td>{{ item.accessExpiresAt }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else>暂无可见活动会话。</p>
    </section>

    <p v-if="feedback" role="status">{{ feedback }}</p>
  </main>
</template>

<style scoped>
.phase09-page{display:grid;gap:1.25rem;max-width:72rem;margin:0 auto;padding:1.5rem}.phase09-kicker{font-weight:700}.phase09-card{display:grid;gap:.8rem;padding:1.25rem;border:1px solid var(--sgj-border,#d8dee9);border-radius:1rem;background:var(--sgj-surface,#fff)}label{display:grid;gap:.35rem}input{min-height:2.75rem;padding:.65rem .8rem;border:1px solid var(--sgj-border,#cbd5e1);border-radius:.65rem}.phase09-actions{display:flex;gap:.75rem;flex-wrap:wrap}button{min-height:2.75rem;padding:.6rem 1rem;border-radius:.65rem}.phase09-secret{display:grid;gap:.35rem;padding:.8rem;background:var(--sgj-surface-muted,#f8fafc);border-radius:.65rem;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.65rem;border-bottom:1px solid var(--sgj-border,#e2e8f0);font-size:.9rem;overflow-wrap:anywhere}
</style>
