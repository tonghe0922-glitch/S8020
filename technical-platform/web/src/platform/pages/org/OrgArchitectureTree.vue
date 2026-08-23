<script setup lang="ts">
import { computed } from 'vue'
import type { OrgArchitectureNode } from '../../../contracts'
import { orgTypeLabel } from './org-architecture-view-model'

const props = withDefaults(defineProps<{
  nodes: OrgArchitectureNode[]
  selectedId?: string
  compact?: boolean
}>(), {
  selectedId: '',
  compact: false,
})

const emit = defineEmits<{ select: [nodeId: string] }>()

const rows = computed(() => props.nodes.map((node) => ({
  node,
  depth: Math.max(0, node.path ? node.path.split('.').length - 1 : inferredDepth(node)),
})))

function inferredDepth(node: OrgArchitectureNode): number {
  let depth = 0
  let current = node
  const visited = new Set<string>()
  while (current.parentId && !visited.has(current.id)) {
    visited.add(current.id)
    const parent = props.nodes.find((candidate) => candidate.id === current.parentId)
    if (!parent) break
    depth += 1
    current = parent
  }
  return depth
}
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
      @click="emit('select', row.node.id)"
    >
      <span class="org-tree__branch" aria-hidden="true" />
      <span class="org-tree__type" :data-type="row.node.orgType">{{ orgTypeLabel(row.node.orgType) }}</span>
      <span class="org-tree__content">
        <strong>{{ row.node.orgName }}</strong>
        <small>{{ row.node.orgCode }} · {{ row.node.memberCount }} 人</small>
      </span>
      <span class="org-tree__status">{{ row.node.status === 'ACTIVE' ? '启用' : '停用' }}</span>
    </button>
    <p v-if="!rows.length" class="org-tree__empty">暂无组织节点</p>
  </div>
</template>

<style scoped>
.org-tree { display: grid; gap: .45rem; }
.org-tree__row {
  --indent: calc(var(--org-depth) * 1.25rem);
  display: grid; grid-template-columns: var(--indent) auto minmax(0, 1fr) auto;
  align-items: center; gap: .65rem; width: 100%; padding: .72rem .8rem;
  border: 1px solid var(--color-border, #e8ddd3); border-radius: .85rem;
  background: var(--color-surface, #fff); text-align: left; cursor: pointer;
}
.org-tree__row:hover, .org-tree__row.is-selected { border-color: var(--color-primary, #d76b2f); box-shadow: 0 8px 24px rgb(70 34 15 / 8%); }
.org-tree__row.is-inactive { opacity: .62; }
.org-tree__branch { width: var(--indent); height: 1px; background: color-mix(in srgb, var(--color-primary, #d76b2f) 30%, transparent); }
.org-tree__type { padding: .22rem .45rem; border-radius: 999px; font-size: .72rem; font-weight: 700; background: #f5efe8; white-space: nowrap; }
.org-tree__type[data-type="MANAGEMENT"] { background: #fbe8df; }
.org-tree__type[data-type="DIRECT"] { background: #f2e7fb; }
.org-tree__type[data-type="CENTER"] { background: #e8f1fb; }
.org-tree__type[data-type="DEPARTMENT"] { background: #e8f7ef; }
.org-tree__type[data-type="GROUP"] { background: #f4f2e6; }
.org-tree__content { min-width: 0; display: grid; gap: .18rem; }
.org-tree__content strong, .org-tree__content small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.org-tree__content small { color: var(--color-text-muted, #776b62); }
.org-tree__status { font-size: .75rem; color: var(--color-text-muted, #776b62); }
.org-tree.is-compact .org-tree__row { padding-block: .48rem; }
.org-tree__empty { margin: 0; padding: 2rem; text-align: center; color: var(--color-text-muted, #776b62); }
</style>
