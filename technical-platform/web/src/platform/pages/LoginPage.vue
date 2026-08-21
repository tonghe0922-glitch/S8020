<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiClientError } from '../../api'
import { SgjButton, SgjCard, SgjError, SgjInput, SgjPortalShell, SgjStatusChip } from '../../design-system'
import { usePortalSessionStore } from '../../session'
import { safeInternalRedirect } from '../../router/redirect'
import type { PortalDefinition } from '../portal-config'

const props = defineProps<{ portal: PortalDefinition }>()
const route = useRoute()
const router = useRouter()
const session = usePortalSessionStore()
const tenantCode = ref('')
const loginName = ref('')
const password = ref('')
const mfaCode = ref('')
const errorMessage = ref('')
const requestId = ref<string | undefined>()
const notice = computed(() => {
  if (route.query.notice === 'expired') return '会话已过期，请重新登录。'
  if (route.query.notice === 'signed_out') return '当前会话已退出。'
  if (route.query.notice === 'logout-unconfirmed') return '本地会话已清除，但服务端退出未确认；请在网络恢复后重新登录。'
  return ''
})

function showFailure(cause: unknown): void {
  errorMessage.value = cause instanceof ApiClientError && cause.status === 401
    ? '登录信息或 MFA 验证码无效，请检查后重试。'
    : '暂时无法登录，请稍后重试。'
  requestId.value = cause instanceof ApiClientError ? cause.requestId : undefined
}

async function submit(): Promise<void> {
  errorMessage.value = ''
  requestId.value = undefined
  try {
    const normalizedMfaCode = mfaCode.value.trim()
    await session.login({
      tenantCode: tenantCode.value.trim(),
      loginName: loginName.value.trim(),
      password: password.value,
      mfaCode: normalizedMfaCode || null,
    })
    password.value = ''
    mfaCode.value = ''
    await router.replace(safeInternalRedirect(route.query.redirect))
  } catch (cause) {
    password.value = ''
    mfaCode.value = ''
    showFailure(cause)
  }
}
</script>

<template>
  <SgjPortalShell :portal-label="props.portal.title" page-title="登录">
    <div class="platform-login-layout">
      <SgjStatusChip v-if="notice" tone="warning">{{ notice }}</SgjStatusChip>
      <SgjCard>
        <template #header>
          <strong>{{ props.portal.title }}</strong>
          <p>{{ props.portal.description }}</p>
        </template>
        <form class="platform-login-form" @submit.prevent="submit">
          <SgjInput v-model="tenantCode" label="租户编码" name="tenantCode" autocomplete="organization" required />
          <SgjInput v-model="loginName" label="登录账号" name="username" autocomplete="username" required />
          <SgjInput v-model="password" label="密码" name="password" type="password" autocomplete="current-password" required />
          <SgjInput
            v-model="mfaCode"
            label="MFA 验证码（已启用时填写）"
            name="mfaCode"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength="6"
          />
          <SgjButton type="submit" :loading="session.phase === 'authenticating'" block>登录</SgjButton>
        </form>
        <SgjError
          v-if="errorMessage"
          title="登录失败"
          :description="errorMessage"
          :trace-id="requestId"
        />
      </SgjCard>
    </div>
  </SgjPortalShell>
</template>


<style scoped>
.platform-login-layout {
  width: min(440px, calc(100% - 32px));
  display: grid;
  gap: var(--sgj-space-4);
  margin: clamp(16px, 5vh, 64px) auto 0;
}

.platform-login-layout :deep(.sgj-card) {
  width: 100%;
}

.platform-login-layout :deep(.sgj-card__header),
.platform-login-layout :deep(.sgj-card__body) {
  padding-inline: clamp(20px, 5vw, 32px);
}

.platform-login-layout :deep(.sgj-card__header p) {
  margin: var(--sgj-space-2) 0 0;
}

.platform-login-form {
  display: grid;
  gap: var(--sgj-space-4);
}

@media (max-width: 480px) {
  .platform-login-layout {
    width: calc(100% - 32px);
    margin-top: 16px;
  }
}
</style>
