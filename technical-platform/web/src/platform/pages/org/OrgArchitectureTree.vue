<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { OrgArchitectureNode } from '../../../contracts'
import { orgTypeLabel } from './org-architecture-view-model'

const props = withDefaults(defineProps<{
  nodes: OrgArchitectureNode[]
  selectedId?: string
  compact?: boolean
  query?: string
  typeFilter?: string
  statusFilter?: string
}>(), {
  selectedId: '',
  compact: false,
  query: '',
  typeFilter: '',
  statusFilter: '',
})

const emit = defineEmits<{ select: [nodeId: string] }>()
const expandedIds = ref<Set<string>>(new Set())

const nodeMap = computed(() => new Map(props.nodes.map((node) => [node.id, node])))
const childrenMap = computed(() => {
  const result = new Map<string, OrgArchitectureNode[]>()
  for (const node of props.nodes) {
    const key = node.parentId ?? '__root__'
    const siblings = result.get(key) ?? []
    siblings.push(node)
    result.set(key, siblings)
  }
  for (const siblings of result.values()) {
    siblings.sort((left, right) => left.sortNo - right.sortNo || left.orgName.localeCompare(right.orgName, 'zh-CN'))
  }
  return result
})

watch(
  () => props.nodes.map((node) => node.id).join('|'),
  () => {
    if (!expandedIds.value.size) expandedIds.value = new Set(props.nodes.map((node) => node.id))
  },
  { immediate: true },
)

const matchingIds = computed(() => {
  const keyword = props.query.trim().toLocaleLowerCase('zh-CN')
  const directMatches = new Set<string>()
  for (const node of props.nodes) {
    const matchesQuery = !keyword || [node.orgName, node.orgCode, orgTypeLabel(node.orgType)]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
    const matchesType = !props.typeFilter || node.orgType === props.typeFilter
    const matchesStatus = !props.statusFilter || node.status === props.statusFilter
    if (matchesQuery && matchesType && matchesStatus) directMatches.add(node.id)
  }
  if (!keyword && !props.typeFilter && !props.statusFilter) return directMatches

  const visible = new Set(directMatches)
  for (const id of directMatches) {
    let current = nodeMap.value.get(id)
    while (current?.parentId) {
      visible.add(current.parentId)
      current = nodeMap.value.get(current.parentId)
    }
  }
  return visible
})

interface TreeRow {
  node: OrgArchitectureNode
  depth: number
  childCount: number
  expanded: boolean
}

const rows = computed<TreeRow[]>(() => {
  const output: TreeRow[] = []
  const filtering = Boolean(props.query.trim() || props.typeFilter || props.statusFilter)
  const visit = (node: OrgArchitectureNode, depth: number): void => {
    if (!matchingIds.value.has(node.id)) return
    const children = childrenMap.value.get(node.id) ?? []
    const expanded = filtering || expandedIds.value.has(node.id)
    output.push({ node, depth, childCount: children.length, expanded })
    if (expanded) children.forEach((child) => visit(child, depth + 1))
  }
  const roots = childrenMap.value.get('__root__') ?? []
  roots.forEach((root) => visit(root, 0))
  return output
})

function toggle(nodeId: string): void {
  const next = new Set(expandedIds.value)
  if (next.has(nodeId)) next.delete(nodeId)
  else next.add(nodeId)
  expandedIds.value = next
}

function expandAll(): void {
  expandedIds.value = new Set(props.nodes.map((node) => node.id))
}

function collapseAll(): void {
  expandedIds.value = new Set()
}

defineExpose({ expandAll, collapseAll })
</script>

<template>
  <div class="org-tree" :class="{ 'is-compact': compact }" role="tree" aria-label="企业组织架构">
    <button
      v-for="row in rows"
      :key="row.node.id"
      type="button"
      role="treeitem"
      class="org-tree__row"
      :class="{
        'is-selected': row.node.id === selectedId,
        'is-inactive': row.node.status !== 'ACTIVE',
      }"
      :style="{ '--org-depth': String(row.depth) }"
      :aria-selected="row.node.id === selectedId"
      @click="emit('select', row.node.id)"
    >
      <span class="org-tree__indent" aria-hidden="true" />
      <span
        class="org-tree__toggle"
        :class="{ 'is-empty': !row.childCount, 'is-expanded': row.expanded }"
        aria-hidden="true"
        @click.stop="row.childCount && toggle(row.node.id)"
      >
        <svg v-if="row.childCount" viewBox="0 0 16 16"><path d="m5.5 3.5 5 4.5-5 4.5" /></svg>
      </span>
      <span class="org-tree__icon" :data-type="row.node.orgType" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4M8 8.5h2M8 12h2M8 15.5h2M16.5 13.5h.1M16.5 16.5h.1" />
        </svg>
      </span>
      <span class="org-tree__content">
        <span class="org-tree__name-line">
          <strong>{{ row.node.orgName }}</strong>
          <span v-if="row.childCount" class="org-tree__children">{{ row.childCount }}</span>
        </span>
        <small>{{ orgTypeLabel(row.node.orgType) }} · {{ row.node.orgCode }}</small>
      </span>
      <span class="org-tree__people">{{ row.node.memberCount }}<small>人</small></span>
      <span class="org-tree__status" :class="{ 'is-on': row.node.status === 'ACTIVE' }">
        {{ row.node.status === 'ACTIVE' ? '启用' : '停用' }}
      </span>
    </button>
    <div v-if="!rows.length" class="org-tree__empty">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5h5l1.7 2H20v9H4zM4 7.5V5h6l1.5 2.5" /></svg>
      <strong>没有匹配的组织</strong>
      <span>请调整搜索内容或筛选条件</span>
    </div>
  </div>
</template>

<style scoped>
.org-tree { display: grid; gap: .25rem; min-width: 0; }
.org-tree__row {
  --indent: calc(var(--org-depth) * 1.15rem);
  position: relative;
  display: grid;
  grid-template-columns: var(--indent) 1.1rem 2.15rem minmax(0, 1fr) auto auto;
  align-items: center;
  gap: .48rem;
  width: 100%;
  min-height: 3.65rem;
  padding: .5rem .55rem .5rem .35rem;
  border: 1px solid transparent;
  border-radius: .72rem;
  background: transparent;
  color: #273248;
  text-align: left;
  cursor: pointer;
  transition: background .18s ease, border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}
.org-tree__row::before {
  position: absolute;
  inset: .42rem auto .42rem 0;
  width: 3px;
  border-radius: 999px;
  background: transparent;
  content: '';
}
.org-tree__row:hover { background: #f6f8fb; }
.org-tree__row.is-selected {
  border-color: #f2cdb9;
  background: linear-gradient(90deg, #fff5ee, #fffaf7);
  box-shadow: 0 8px 24px rgb(134 70 31 / 8%);
}
.org-tree__row.is-selected::before { background: #e66d32; }
.org-tree__row.is-inactive { opacity: .58; }
.org-tree__indent { width: var(--indent); }
.org-tree__toggle { display: grid; place-items: center; width: 1.1rem; height: 1.1rem; color: #8b95a5; }
.org-tree__toggle svg { width: .85rem; height: .85rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; transition: transform .18s ease; }
.org-tree__toggle.is-expanded svg { transform: rotate(90deg); }
.org-tree__toggle.is-empty { opacity: 0; }
.org-tree__icon { display: grid; place-items: center; width: 2.15rem; height: 2.15rem; border-radius: .62rem; background: #f1f4f8; color: #68758b; }
.org-tree__icon svg { width: 1.25rem; height: 1.25rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.55; }
.org-tree__icon[data-type="MANAGEMENT"] { background: #fff0e7; color: #db632b; }
.org-tree__icon[data-type="DIRECT"] { background: #f4edff; color: #8358b5; }
.org-tree__icon[data-type="COMPANY"] { background: #eaf3ff; color: #3f77b7; }
.org-tree__icon[data-type="CENTER"] { background: #eaf7ff; color: #2783ac; }
.org-tree__icon[data-type="DEPARTMENT"] { background: #ebf8f1; color: #338866; }
.org-tree__icon[data-type="GROUP"] { background: #fff8df; color: #9a7925; }
.org-tree__content { display: grid; gap: .16rem; min-width: 0; }
.org-tree__name-line { display: flex; align-items: center; gap: .38rem; min-width: 0; }
.org-tree__content strong { overflow: hidden; font-size: .86rem; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.org-tree__content small { overflow: hidden; color: #8b95a5; font-size: .69rem; text-overflow: ellipsis; white-space: nowrap; }
.org-tree__children { display: inline-grid; flex: 0 0 auto; place-items: center; min-width: 1.15rem; height: 1.15rem; padding: 0 .25rem; border-radius: 999px; background: #eef1f5; color: #758096; font-size: .62rem; font-weight: 700; }
.org-tree__people { color: #59657a; font-size: .78rem; font-weight: 750; white-space: nowrap; }
.org-tree__people small { margin-left: .1rem; color: #9aa3b2; font-size: .62rem; font-weight: 500; }
.org-tree__status { padding: .18rem .38rem; border-radius: 999px; background: #f1f2f5; color: #8c95a3; font-size: .62rem; font-weight: 700; white-space: nowrap; }
.org-tree__status.is-on { background: #eaf8ef; color: #3f8a5a; }
.org-tree.is-compact .org-tree__row { min-height: 3.15rem; }
.org-tree.is-compact .org-tree__icon { width: 1.9rem; height: 1.9rem; }
.org-tree__empty { display: grid; place-items: center; gap: .35rem; padding: 3rem 1rem; color: #9aa3b2; text-align: center; }
.org-tree__empty svg { width: 2.4rem; height: 2.4rem; fill: none; stroke: #bdc4cf; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.35; }
.org-tree__empty strong { color: #69758a; font-size: .9rem; }
.org-tree__empty span { font-size: .75rem; }
@media (max-width: 680px) {
  .org-tree__row { grid-template-columns: var(--indent) 1rem 2rem minmax(0, 1fr) auto; }
  .org-tree__status { display: none; }
}
</style>
