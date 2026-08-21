<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  portalLabel: string
  pageTitle: string
  contentId?: string
  sidebarLabel?: string
  bottomNavLabel?: string
  globalAlertLabel?: string
  toastRegionLabel?: string
  assistantLabel?: string
  sidebarCollapsed?: boolean
}>(), {
  contentId: 'sgj-main-content',
  sidebarLabel: '主导航',
  bottomNavLabel: '移动端导航',
  globalAlertLabel: '全局告警',
  toastRegionLabel: '全局提示',
  assistantLabel: '辅助入口',
  sidebarCollapsed: false,
})

const mainRef = ref<HTMLElement | null>(null)

function focusMain(event: MouseEvent): void {
  event.preventDefault()
  mainRef.value?.focus()
}
</script>

<template>
  <div
    class="sgj-portal-shell"
    :class="{
      'sgj-portal-shell--sidebar-collapsed': sidebarCollapsed && Boolean($slots.sidebar),
      'sgj-portal-shell--no-sidebar': !$slots.sidebar,
    }"
  >
    <a class="sgj-skip-link" :href="`#${contentId}`" @click="focusMain">跳到主要内容</a>
    <header class="sgj-portal-shell__header">
      <div class="sgj-portal-shell__brand" :aria-label="`当前端口：${portalLabel}`">{{ portalLabel }}</div>
      <div class="sgj-portal-shell__header-slot"><slot name="header" /></div>
    </header>
    <section v-if="$slots.globalAlert" class="sgj-portal-shell__global-alert" :aria-label="globalAlertLabel">
      <slot name="globalAlert" />
    </section>
    <aside v-if="$slots.sidebar" class="sgj-portal-shell__sidebar" :aria-label="sidebarLabel">
      <slot name="sidebar" :collapsed="sidebarCollapsed" />
    </aside>
    <main ref="mainRef" :id="contentId" class="sgj-portal-shell__main" tabindex="-1">
      <h1 class="sgj-portal-shell__title">{{ pageTitle }}</h1>
      <slot />
    </main>
    <nav v-if="$slots.bottomNav" class="sgj-portal-shell__bottom-nav" :aria-label="bottomNavLabel">
      <slot name="bottomNav" />
    </nav>
    <section v-if="$slots.toastRegion" class="sgj-portal-shell__toast-region" :aria-label="toastRegionLabel">
      <slot name="toastRegion" />
    </section>
    <aside v-if="$slots.assistant" class="sgj-portal-shell__assistant" :aria-label="assistantLabel">
      <slot name="assistant" />
    </aside>
  </div>
</template>
