<script setup lang="ts">
import { computed } from 'vue'
import type { SessionView } from '../contracts'
import { SgjButton, SgjSelect } from '../design-system'
import type { PortalDefinition } from './portal-config'

const props = withDefaults(defineProps<{
  portal: PortalDefinition
  session: SessionView
  switching?: boolean
}>(), {
  switching: false,
})

const emit = defineEmits<{
  switchIdentity: [identityId: string]
  logout: []
}>()

const options = computed(() => props.session.availableIdentities.map((identity) => ({
  value: identity.identityId,
  label: identity.identityName,
})))
const currentIdentity = computed(() => props.session.availableIdentities
  .find((identity) => identity.identityId === props.session.identityId))
const identityName = computed(() => currentIdentity.value?.identityName ?? '当前身份')
const identityInitial = computed(() => Array.from(identityName.value.replaceAll(' ', ''))[0] ?? '员')
const canSwitch = computed(() => props.session.permissions.includes('platform.session.switch'))

function switchIdentity(identityId: string): void {
  if (identityId && identityId !== props.session.identityId) emit('switchIdentity', identityId)
}
</script>

<template>
  <div class="portal-session-header">
    <div class="portal-session-header__context" :aria-label="`${portal.title}，当前身份 ${identityName}`">
      <span class="portal-session-header__avatar" aria-hidden="true">{{ identityInitial }}</span>
      <span class="portal-session-header__copy">
        <small>{{ portal.title }}</small>
        <strong>{{ identityName }}<i aria-hidden="true" /></strong>
      </span>
    </div>
    <SgjSelect
      v-if="canSwitch && options.length > 1"
      class="portal-session-header__identity"
      label="切换当前身份"
      :model-value="session.identityId"
      :options="options"
      :disabled="switching"
      @update:model-value="switchIdentity"
    />
    <SgjButton variant="ghost" :disabled="switching" @click="emit('logout')">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4" />
        <path d="m14 16 4-4-4-4M18 12H9" />
      </svg>
      退出登录
    </SgjButton>
  </div>
</template>

<style scoped>
.portal-session-header {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--sgj-space-2);
}

.portal-session-header__context {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 10px 4px 5px;
  border: 1px solid var(--sgj-border);
  border-radius: var(--sgj-radius-pill);
  background: rgba(15, 23, 42, .025);
}

.portal-session-header__avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(135deg, var(--sgj-brand-400), var(--sgj-brand-600));
  box-shadow: 0 3px 9px rgba(234, 88, 12, .22);
  font-size: var(--sgj-font-sm);
  font-weight: 750;
}

.portal-session-header__copy {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.portal-session-header__copy small {
  color: var(--sgj-text-tertiary);
  font-size: 10px;
  line-height: 1.2;
}

.portal-session-header__copy strong {
  max-width: 180px;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  color: var(--sgj-text-primary);
  font-size: var(--sgj-font-sm);
  font-weight: 700;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.portal-session-header__copy i {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--sgj-success);
  box-shadow: 0 0 0 3px rgba(5, 150, 105, .10);
}

.portal-session-header__identity {
  width: min(238px, 29vw);
}

.portal-session-header :deep(.portal-session-header__identity .sgj-field__label) {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}

.portal-session-header :deep(.portal-session-header__identity .sgj-control) {
  min-height: 40px;
  padding-block: 6px;
  border-radius: var(--sgj-radius-pill);
  background: rgba(15, 23, 42, .025);
}

.portal-session-header :deep(.sgj-button) {
  min-height: 40px;
  border-radius: var(--sgj-radius-pill);
}

.portal-session-header :deep(.sgj-button svg) {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

@media (max-width: 900px) {
  .portal-session-header__identity { display: none; }
}

@media (max-width: 640px) {
  .portal-session-header__context { padding-right: 5px; }
  .portal-session-header__copy { display: none; }
  .portal-session-header :deep(.sgj-button) {
    width: 40px;
    padding: 0;
    font-size: 0;
  }
  .portal-session-header :deep(.sgj-button svg) {
    width: 19px;
    height: 19px;
  }
}
</style>
