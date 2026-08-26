<script setup lang="ts">
import { computed, ref } from 'vue'
import type { OrgArchitectureNode } from '../../../contracts'
import { orgTypeLabel } from './org-architecture-view-model'

defineOptions({ name: 'OrgArchitectureChart' })

const props = withDefaults(defineProps<{
  nodes: OrgArchitectureNode[]
  selectedId?: string
  rootId?: string
  compact?: boolean
}>(), {
  selectedId: '',
  rootId: '',
  compact: false,
})

const emit = defineEmits<{ select: [nodeId: string] }>()
const collapsed = ref(false)

const node = computed(() => props.nodes.find((candidate) => candidate.id === props.rootId) ?? null)
const roots = computed(() => props.nodes
  .filter((candidate) => !candidate.parentId || !props.nodes.some((parent) => parent.id === candidate.parentId))
  .sort(compareNodes))
const children = computed(() => props.nodes
  .filter((candidate) => candidate.parentId === props.rootId)
  .sort(compareNodes))

function compareNodes(left: OrgArchitectureNode, right: OrgArchitectureNode): number {
  return left.sortNo - right.sortNo || left.orgName.localeCompare(right.orgName, 'zh-CN')
}

function initials(value: string): string {
  return value.trim().slice(0, 2)
}
</script>

<template>
  <li v-if="rootId && node" class="org-chart__branch" :class="{ 'is-collapsed': collapsed }">
    <button
      type="button"
      class="org-chart__card"
      :class="{
        'is-selected': node.id === selectedId,
        'is-inactive': node.status !== 'ACTIVE',
      }"
      @click="emit('select', node.id)"
    >
      <span class="org-chart__mark" :data-type="node.orgType">{{ initials(node.orgName) }}</span>
      <span class="org-chart__copy">
        <span class="org-chart__title-line">
          <strong>{{ node.orgName }}</strong>
          <span class="org-chart__type">{{ orgTypeLabel(node.orgType) }}</span>
        </span>
        <small>{{ node.orgCode }}</small>
      </span>
      <span class="org-chart__metric"><strong>{{ node.memberCount }}</strong><small>在岗</small></span>
      <span class="org-chart__metric"><strong>{{ node.headcountPlan }}</strong><small>编制</small></span>
      <span class="org-chart__arrow" aria-hidden="true">›</span>
    </button>

    <button
      v-if="children.length"
      type="button"
      class="org-chart__collapse"
      :aria-label="collapsed ? `展开${node.orgName}下级组织` : `收起${node.orgName}下级组织`"
      @click="collapsed = !collapsed"
    >
      <svg viewBox="0 0 16 16" :class="{ 'is-collapsed': collapsed }"><path d="m4 6 4 4 4-4" /></svg>
      <span>{{ children.length }}</span>
    </button>

    <ul v-if="children.length && !collapsed" class="org-chart__children">
      <OrgArchitectureChart
        v-for="child in children"
        :key="child.id"
        :nodes="nodes"
        :selected-id="selectedId"
        :root-id="child.id"
        :compact="compact"
        @select="emit('select', $event)"
      />
    </ul>
  </li>

  <div v-else-if="!rootId" class="org-chart" :class="{ 'is-compact': compact }">
    <div class="org-chart__canvas">
      <ul v-if="roots.length" class="org-chart__roots">
        <OrgArchitectureChart
          v-for="root in roots"
          :key="root.id"
          :nodes="nodes"
          :selected-id="selectedId"
          :root-id="root.id"
          :compact="compact"
          @select="emit('select', $event)"
        />
      </ul>
      <div v-else class="org-chart__empty">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5h5l1.7 2H20v9H4zM4 7.5V5h6l1.5 2.5" /></svg>
        <strong>暂无组织架构</strong>
        <span>发布组织数据后将在此展示</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.org-chart {
  position: relative;
  overflow: auto;
  min-height: 32rem;
  padding: 1.4rem;
  border-radius: 1rem;
  background:
    linear-gradient(#eef1f5 1px, transparent 1px),
    linear-gradient(90deg, #eef1f5 1px, transparent 1px),
    #f8f9fb;
  background-size: 24px 24px;
  scrollbar-color: #c8ced8 transparent;
  scrollbar-width: thin;
}
.org-chart__canvas { display: grid; min-width: max-content; min-height: 29rem; place-items: start center; padding: .45rem 1rem 4rem; }
.org-chart__roots,
.org-chart__children { display: flex; justify-content: center; margin: 0; padding: 0; list-style: none; }
.org-chart__roots { gap: 2.1rem; }
.org-chart__branch { position: relative; display: flex; align-items: center; flex-direction: column; padding: 1.8rem .7rem 0; }
.org-chart__branch::before,
.org-chart__branch::after { position: absolute; top: 0; right: 50%; width: 50%; height: 1.8rem; border-top: 1px solid #cfd5df; content: ''; }
.org-chart__branch::after { right: auto; left: 50%; border-right: 1px solid #cfd5df; }
.org-chart__branch:only-child::before,
.org-chart__branch:only-child::after { display: none; }
.org-chart__branch:first-child::before,
.org-chart__branch:last-child::after { border: 0; }
.org-chart__branch:last-child::before { border-right: 1px solid #cfd5df; border-radius: 0 .55rem 0 0; }
.org-chart__branch:first-child::after { border-radius: .55rem 0 0 0; }
.org-chart__children { position: relative; margin-top: 1.45rem; }
.org-chart__children::before { position: absolute; top: 0; left: 50%; width: 0; height: 1.8rem; border-left: 1px solid #cfd5df; content: ''; }
.org-chart__card {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: 2.7rem minmax(9.5rem, 1fr) auto auto .85rem;
  align-items: center;
  gap: .68rem;
  min-width: 22rem;
  min-height: 5rem;
  padding: .82rem .9rem;
  border: 1px solid #e1e5eb;
  border-radius: .9rem;
  background: #fff;
  color: #263248;
  box-shadow: 0 8px 24px rgb(39 50 72 / 7%);
  text-align: left;
  cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}
.org-chart__card:hover { border-color: #edb89f; box-shadow: 0 13px 32px rgb(122 65 31 / 12%); transform: translateY(-2px); }
.org-chart__card.is-selected { border-color: #e56d34; box-shadow: 0 0 0 3px #ffede4, 0 14px 34px rgb(132 69 30 / 14%); }
.org-chart__card.is-inactive { opacity: .56; }
.org-chart__mark { display: grid; place-items: center; width: 2.7rem; height: 2.7rem; border-radius: .75rem; background: #edf2fa; color: #55719b; font-size: .76rem; font-weight: 800; }
.org-chart__mark[data-type="MANAGEMENT"] { background: #fff0e8; color: #d75f29; }
.org-chart__mark[data-type="DIRECT"] { background: #f4edff; color: #865bb8; }
.org-chart__mark[data-type="COMPANY"] { background: #eaf2ff; color: #3f72b1; }
.org-chart__mark[data-type="CENTER"] { background: #e8f7ff; color: #237fa8; }
.org-chart__mark[data-type="DEPARTMENT"] { background: #e9f8f0; color: #33825f; }
.org-chart__mark[data-type="GROUP"] { background: #fff7dc; color: #98731d; }
.org-chart__copy { display: grid; gap: .24rem; min-width: 0; }
.org-chart__title-line { display: flex; align-items: center; gap: .42rem; min-width: 0; }
.org-chart__title-line strong { overflow: hidden; font-size: .88rem; text-overflow: ellipsis; white-space: nowrap; }
.org-chart__type { padding: .14rem .36rem; border-radius: 999px; background: #f1f3f6; color: #798397; font-size: .58rem; font-weight: 700; white-space: nowrap; }
.org-chart__copy small { color: #929cab; font-size: .67rem; }
.org-chart__metric { display: grid; min-width: 2.2rem; gap: .08rem; text-align: center; }
.org-chart__metric strong { font-size: .9rem; }
.org-chart__metric small { color: #9aa3b1; font-size: .56rem; }
.org-chart__arrow { color: #b2b9c4; font-size: 1.25rem; }
.org-chart__collapse { position: relative; z-index: 3; display: flex; align-items: center; gap: .18rem; margin-top: .45rem; padding: .16rem .38rem; border: 1px solid #dfe4eb; border-radius: 999px; background: #fff; color: #8c96a5; font: inherit; font-size: .6rem; cursor: pointer; }
.org-chart__collapse svg { width: .7rem; height: .7rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; transition: transform .18s ease; }
.org-chart__collapse svg.is-collapsed { transform: rotate(-90deg); }
.org-chart__branch.is-collapsed { padding-bottom: 1rem; }
.org-chart.is-compact { min-height: 24rem; padding: .8rem; }
.org-chart.is-compact .org-chart__canvas { min-height: 22rem; }
.org-chart.is-compact .org-chart__card { grid-template-columns: 2.35rem minmax(8rem, 1fr) auto .7rem; min-width: 17.5rem; min-height: 4.3rem; }
.org-chart.is-compact .org-chart__mark { width: 2.35rem; height: 2.35rem; }
.org-chart.is-compact .org-chart__metric:nth-of-type(2) { display: none; }
.org-chart__empty { display: grid; place-items: center; gap: .35rem; min-width: 32rem; min-height: 24rem; color: #9aa3b2; text-align: center; }
.org-chart__empty svg { width: 3rem; height: 3rem; fill: none; stroke: #bdc4cf; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.3; }
.org-chart__empty strong { color: #667287; }
.org-chart__empty span { font-size: .78rem; }
@media (max-width: 760px) {
  .org-chart { min-height: 28rem; padding: .8rem; }
  .org-chart__card { grid-template-columns: 2.35rem minmax(8rem, 1fr) auto .7rem; min-width: 17.5rem; }
  .org-chart__mark { width: 2.35rem; height: 2.35rem; }
  .org-chart__metric:nth-of-type(2) { display: none; }
}
</style>
