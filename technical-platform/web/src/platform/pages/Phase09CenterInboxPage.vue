<script setup lang="ts">
import { computed } from 'vue'
import { usePortalSessionStore } from '../../session'
import type { PortalDefinition } from '../portal-config'
import P001IdentityPage from './P001IdentityPage.vue'
import P002PermissionRequestPage from './P002PermissionRequestPage.vue'
import P003ProfileChangePage from './P003ProfileChangePage.vue'
import P004GenericRequestPage from './P004GenericRequestPage.vue'

const props = defineProps<{ portal: PortalDefinition }>()
const session = usePortalSessionStore()

const showIdentityMonitoring = computed(() => session.can('p001.session.monitor'))
const showPermissionRequests = computed(() => (
  session.can('p002.request.read') || session.can('p002.request.review')
))
const showProfileChanges = computed(() => (
  session.can('p003.change.read') || session.can('p003.change.review')
))
const showGeneralRequests = computed(() => (
  session.can('p004.request.read') || session.can('p004.request.act')
))
const hasVisibleCapability = computed(() => (
  showIdentityMonitoring.value
  || showPermissionRequests.value
  || showProfileChanges.value
  || showGeneralRequests.value
))
</script>

<template>
  <main class="center-review-inbox" data-testid="center-review-inbox">
    <header class="center-review-inbox__header">
      <p class="center-review-inbox__eyebrow">共享审批与监督入口</p>
      <h1>中心审批与监督</h1>
      <p>
        页面根据当前服务端权限展示相应业务能力。页面隐藏不构成授权边界，所有接口仍由后端重新鉴权。
      </p>
    </header>

    <P001IdentityPage
      v-if="showIdentityMonitoring"
      :portal="props.portal"
      audience="center"
    />
    <P002PermissionRequestPage
      v-if="showPermissionRequests"
      :portal="props.portal"
      mode="center"
    />
    <P003ProfileChangePage
      v-if="showProfileChanges"
      :portal="props.portal"
      mode="center"
    />
    <P004GenericRequestPage
      v-if="showGeneralRequests"
      :portal="props.portal"
      mode="center"
    />

    <section v-if="!hasVisibleCapability" class="center-review-inbox__empty">
      当前身份没有此共享入口下的读取或处理权限。
    </section>
  </main>
</template>

<style scoped>
.center-review-inbox {
  display: grid;
  gap: var(--sgj-space-5);
}

.center-review-inbox__header {
  width: min(76rem, 100%);
  margin: 0 auto;
  padding: var(--sgj-space-6) var(--sgj-space-6) 0;
}

.center-review-inbox__eyebrow {
  color: var(--sgj-brand-700);
  font-weight: 700;
}

.center-review-inbox__empty {
  width: min(76rem, calc(100% - 3rem));
  margin: 0 auto var(--sgj-space-6);
  padding: var(--sgj-space-4);
  border: 1px solid var(--sgj-border);
  border-radius: var(--sgj-radius-md);
  background: var(--sgj-surface);
}
</style>
