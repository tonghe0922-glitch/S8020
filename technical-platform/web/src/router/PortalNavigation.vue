<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { RuntimePortCode } from '../platform/portal-config'
import { usePortalSessionStore } from '../session'
import NavigationIcon from './NavigationIcon.vue'
import {
  projectNavigationGroups,
  projectedItemIsActive,
  resolveActiveGroup,
  splitProjectedMobileNavigation,
  type NavigationRouteAccessRule,
  type ProjectedNavigationGroup,
  type ProjectedNavigationItem,
} from './navigation-projection'

const props = withDefaults(defineProps<{
  runtimeCode: RuntimePortCode
  mobile?: boolean
  identityLabel?: string | null
}>(), {
  mobile: false,
  identityLabel: null,
})

const route = useRoute()
const router = useRouter()
const session = usePortalSessionStore()
const expanded = ref<Set<string>>(new Set())

const implementedRoutePaths = computed(() => new Set(router.getRoutes().map((record) => record.path)))
const routeAccessRules = computed<ReadonlyMap<string, NavigationRouteAccessRule>>(() => {
  const rules = new Map<string, NavigationRouteAccessRule>()
  for (const record of router.getRoutes()) {
    const all = typeof record.meta.permission === 'string' ? [record.meta.permission] : []
    const any = Array.isArray(record.meta.permissionsAny)
      ? record.meta.permissionsAny.filter((value): value is string => typeof value === 'string')
      : []
    if (all.length || any.length) rules.set(record.path, { all, any })
  }
  return rules
})

const groups = computed(() => projectNavigationGroups({
  runtimeCode: props.runtimeCode,
  permissions: new Set(session.session?.permissions ?? []),
  implementedRoutePaths: implementedRoutePaths.value,
  routeAccessRules: routeAccessRules.value,
  mobile: props.mobile,
  identityLabel: props.identityLabel,
}))
const mobileGroups = computed(() => splitProjectedMobileNavigation(groups.value))
const activeGroup = computed(() => resolveActiveGroup(groups.value, route.path, route.query.module))

function toggleGroup(key: string): void {
  const next = new Set(expanded.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expanded.value = next
}

function isExpanded(group: ProjectedNavigationGroup): boolean {
  return expanded.value.has(group.key) || activeGroup.value?.key === group.key
}

function isActive(item: ProjectedNavigationItem): boolean {
  return projectedItemIsActive(route.path, route.query.module, item)
}

function groupTarget(group: ProjectedNavigationGroup): string {
  return (group.items.find((item) => item.state === 'implemented') ?? group.items[0])?.routePath ?? '/'
}

watch(
  () => activeGroup.value?.key,
  (key) => {
    if (!key) return
    const next = new Set(expanded.value)
    next.add(key)
    expanded.value = next
  },
  { immediate: true },
)
</script>

<template>
  <nav class="portal-navigation" :class="{ 'portal-navigation--mobile': props.mobile }">
    <template v-if="!props.mobile">
      <section
        v-for="group in groups"
        :key="group.key"
        class="portal-navigation__group"
        :data-navigation-key="group.key"
      >
        <RouterLink
          v-if="group.items.length === 1"
          class="portal-navigation__top-link"
          :class="{ 'is-active': isActive(group.items[0]!) }"
          :to="group.items[0]!.routePath"
        >
          <NavigationIcon :icon="group.iconKey" />
          <span>{{ group.label }}</span>
          <small v-if="group.items[0]!.state !== 'implemented'">
            {{ group.items[0]!.state === 'developing' ? '开发中' : '无权限' }}
          </small>
        </RouterLink>

        <template v-else>
          <button
            class="portal-navigation__group-button"
            :class="{ 'is-active': activeGroup?.key === group.key }"
            type="button"
            :aria-expanded="isExpanded(group)"
            @click="toggleGroup(group.key)"
          >
            <NavigationIcon :icon="group.iconKey" />
            <span>{{ group.label }}</span>
            <small v-if="group.centerScoped && group.contextLabel">{{ group.contextLabel }}</small>
            <b aria-hidden="true">{{ isExpanded(group) ? '−' : '+' }}</b>
          </button>
          <div v-show="isExpanded(group)" class="portal-navigation__children">
            <RouterLink
              v-for="item in group.items"
              :key="item.key"
              class="portal-navigation__child-link"
              :class="{ 'is-active': isActive(item) }"
              :to="item.routePath"
              :data-navigation-key="item.key"
            >
              <span>{{ item.label }}</span>
              <small v-if="item.state !== 'implemented'">
                {{ item.state === 'developing' ? '开发中' : '无权限' }}
              </small>
            </RouterLink>
          </div>
        </template>
      </section>
    </template>

    <template v-else>
      <RouterLink
        v-for="group in mobileGroups.primary"
        :key="group.key"
        class="portal-navigation__mobile-link"
        :class="{ 'is-active': activeGroup?.key === group.key }"
        :to="groupTarget(group)"
      >
        <NavigationIcon :icon="group.iconKey" />
        <span>{{ group.label }}</span>
      </RouterLink>
      <details v-if="mobileGroups.overflow.length" class="portal-navigation__more">
        <summary>更多</summary>
        <div class="portal-navigation__more-menu">
          <RouterLink
            v-for="group in mobileGroups.overflow"
            :key="group.key"
            :class="{ 'is-active': activeGroup?.key === group.key }"
            :to="groupTarget(group)"
          >
            {{ group.label }}
          </RouterLink>
        </div>
      </details>
    </template>
  </nav>
</template>

<style scoped>
.portal-navigation {
  display: grid;
  gap: 4px;
}

.portal-navigation__group { min-width: 0; }
.portal-navigation__top-link,
.portal-navigation__group-button {
  width: 100%;
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 0;
  border-radius: 12px;
  color: #475569;
  background: transparent;
  font: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.portal-navigation__top-link:hover,
.portal-navigation__group-button:hover,
.portal-navigation__top-link.is-active,
.portal-navigation__group-button.is-active {
  color: #c2410c;
  background: #fff7ed;
}

.portal-navigation :deep(svg) {
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.portal-navigation__top-link > span,
.portal-navigation__group-button > span { flex: 1; }
.portal-navigation small { color: #94a3b8; font-size: 10px; }
.portal-navigation__group-button b { font-weight: 500; }

.portal-navigation__children {
  display: grid;
  gap: 2px;
  padding: 2px 0 6px 38px;
}

.portal-navigation__child-link {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 9px;
  border-radius: 9px;
  color: #64748b;
  font-size: 13px;
  text-decoration: none;
}

.portal-navigation__child-link:hover,
.portal-navigation__child-link.is-active {
  color: #ea580c;
  background: #fff7ed;
}

.portal-navigation--mobile {
  display: flex;
  align-items: center;
  justify-content: space-around;
}

.portal-navigation__mobile-link,
.portal-navigation__more > summary {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: #64748b;
  font-size: 10px;
  text-decoration: none;
}

.portal-navigation__mobile-link.is-active { color: #ea580c; }
.portal-navigation__more { position: relative; }
.portal-navigation__more > summary { list-style: none; cursor: pointer; }
.portal-navigation__more-menu {
  position: absolute;
  right: 0;
  bottom: 42px;
  min-width: 180px;
  padding: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, .12);
}

.portal-navigation__more-menu a {
  display: block;
  padding: 8px;
  color: #475569;
  text-decoration: none;
}

.portal-navigation__more-menu a.is-active {
  color: #ea580c;
  background: #fff7ed;
}
</style>
