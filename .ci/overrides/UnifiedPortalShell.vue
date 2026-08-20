<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  useSlots,
  watch,
} from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { PortalDefinition } from '../../../platform/portal-config'
import type { ShellSearchItem } from './types'

const props = withDefaults(defineProps<{
  portal: PortalDefinition
  pageTitle: string
  searchItems?: readonly ShellSearchItem[]
  contentId?: string
  alertVisible?: boolean
}>(), {
  searchItems: () => [],
  contentId: 'sgj-main-content',
  alertVisible: false,
})

const route = useRoute()
const router = useRouter()
const slots = useSlots()
const brandLogoUrl = new URL('../../../assets/brand/LOGO.svg', import.meta.url).href
const mainRef = ref<HTMLElement | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const query = ref('')
const searchOpen = ref(false)
const sidebarCollapsed = ref(false)
const drawerOpen = ref(false)

const portalTag = computed(() => ({
  work: '工作端',
  tech: '技术端',
})[props.portal.code] ?? '平台端')
const hasAlert = computed(() => props.alertVisible && Boolean(slots.globalAlert))
const isHome = computed(() => route.path === '/')
const searchResults = computed(() => {
  const home: ShellSearchItem = {
    sourceKey: 'portal-home',
    label: '首页',
    routePath: '/',
  }
  const items = [home, ...props.searchItems]
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return items.slice(0, 8)
  return items
    .filter((item) => item.label.toLocaleLowerCase('zh-CN').includes(keyword))
    .slice(0, 8)
})

function focusMain(event: MouseEvent): void {
  event.preventDefault()
  mainRef.value?.focus()
}

function openSearch(): void {
  searchOpen.value = true
  void nextTick(() => searchInputRef.value?.focus())
}

function closeSearch(): void {
  searchOpen.value = false
  query.value = ''
}

function toggleSidebar(): void {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function toggleDrawer(): void {
  drawerOpen.value = !drawerOpen.value
}

async function navigate(path: string): Promise<void> {
  closeSearch()
  drawerOpen.value = false
  await router.push(path)
}

function handleGlobalKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
    event.preventDefault()
    openSearch()
    return
  }
  if (event.key !== 'Escape') return
  closeSearch()
  drawerOpen.value = false
}

function closeTransientNavigation(): void {
  drawerOpen.value = false
  searchOpen.value = false
}

onMounted(() => globalThis.addEventListener('keydown', handleGlobalKeydown))
onBeforeUnmount(() => globalThis.removeEventListener('keydown', handleGlobalKeydown))
watch(() => route.fullPath, closeTransientNavigation)
</script>

<template>
  <div
    class="rebuild-shell"
    :class="{
      'rebuild-shell--has-alert': hasAlert,
      'rebuild-shell--sidebar-collapsed': sidebarCollapsed,
      'rebuild-shell--drawer-open': drawerOpen,
    }"
  >
    <a
      class="rebuild-shell__skip-link"
      :href="`#${contentId}`"
      @click="focusMain"
    >
      跳到主要内容
    </a>

    <header class="rebuild-shell__topbar">
      <button
        class="rebuild-shell__nav-toggle rebuild-shell__nav-toggle--desktop"
        type="button"
        aria-label="展开或收起主导航"
        :aria-expanded="(!sidebarCollapsed).toString()"
        @click="toggleSidebar"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
      </button>
      <button
        class="rebuild-shell__nav-toggle rebuild-shell__nav-toggle--mobile"
        type="button"
        aria-label="打开主导航"
        :aria-expanded="drawerOpen.toString()"
        @click="toggleDrawer"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
      </button>

      <RouterLink class="rebuild-shell__brand" to="/" aria-label="返回上金谷管理平台首页">
        <img class="rebuild-shell__brand-logo" :src="brandLogoUrl" alt="上金谷品牌标志">
        <span class="rebuild-shell__brand-copy">
          <span class="rebuild-shell__brand-title-row">
            <strong>上金谷管理平台</strong>
            <span class="rebuild-shell__brand-tag">{{ portalTag }}</span>
          </span>
          <small>数字化现场调度协同系统</small>
        </span>
      </RouterLink>

      <div class="rebuild-shell__search" :class="{ 'is-open': searchOpen }">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        <input
          ref="searchInputRef"
          v-model="query"
          type="search"
          autocomplete="off"
          aria-label="搜索当前可访问页面"
          placeholder="搜索当前可访问页面"
          data-test="shell-search"
          @focus="searchOpen = true"
        >
        <kbd>Ctrl K</kbd>
        <div v-if="searchOpen" class="rebuild-shell__search-results" role="listbox">
          <button
            v-for="item in searchResults"
            :key="item.sourceKey"
            type="button"
            role="option"
            @click="navigate(item.routePath)"
          >
            <span>{{ item.label }}</span>
            <small>{{ item.routePath }}</small>
          </button>
          <p v-if="!searchResults.length">没有匹配的已开放页面</p>
        </div>
      </div>

      <div class="rebuild-shell__topbar-actions">
        <button
          class="rebuild-shell__search-trigger"
          type="button"
          aria-label="搜索页面"
          @click="openSearch"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
        </button>
        <slot name="header" />
      </div>
    </header>

    <section v-if="hasAlert" class="rebuild-shell__alert" aria-label="全局告警">
      <slot name="globalAlert" />
    </section>

    <aside class="rebuild-shell__sidebar" aria-label="主导航">
      <div class="rebuild-shell__sidebar-heading">
        <span>工作导航</span>
        <small>{{ portal.title }}</small>
      </div>
      <div class="rebuild-shell__sidebar-content"><slot name="sidebar" /></div>
      <div class="rebuild-shell__sidebar-foot">
        <span />
        <small>真实权限与路由已接入</small>
      </div>
    </aside>

    <button
      v-if="drawerOpen"
      class="rebuild-shell__backdrop"
      type="button"
      aria-label="关闭导航"
      @click="drawerOpen = false"
    />

    <main
      :id="contentId"
      ref="mainRef"
      class="rebuild-shell__main"
      tabindex="-1"
    >
      <div class="rebuild-shell__page-head">
        <nav class="rebuild-shell__breadcrumbs" aria-label="面包屑">
          <RouterLink to="/">首页</RouterLink>
          <span>/</span>
          <strong>{{ pageTitle }}</strong>
        </nav>
        <button v-if="!isHome" type="button" @click="router.back()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
          返回上一层
        </button>
      </div>
      <h1 class="rebuild-shell__page-title">{{ pageTitle }}</h1>
      <div class="rebuild-shell__content"><slot /></div>
    </main>

    <nav class="rebuild-shell__bottom-nav" aria-label="移动端导航">
      <slot name="bottomNav" />
    </nav>
  </div>
</template>

<style src="./rebuild-shell.css"></style>
<style src="./brand-topbar.css"></style>
