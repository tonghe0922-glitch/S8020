<script setup lang="ts">
import { computed } from 'vue'
import { SgjButton, SgjStatusChip, SgjTable } from '../../../../design-system'
import { displayNumber, displayTime, statusTone } from '../../shared'
import type { Phase10Action, Phase10Aggregate } from '../../shared'

const props = withDefaults(defineProps<{
  rows: readonly Phase10Aggregate[]
  nodes?: readonly string[]
  plans?: readonly string[]
  actionCodes?: readonly string[]
  busy: boolean
  actionsFor: (aggregate: Phase10Aggregate) => readonly Phase10Action[]
  canAct: (action: string) => boolean
  emptyText?: string
}>(), {
  nodes: () => [],
  plans: () => [],
  actionCodes: () => [],
  emptyText: '当前没有符合条件的加班记录',
})

const emit = defineEmits<{ act: [aggregate: Phase10Aggregate, action: string] }>()
const visibleRows = computed(() => props.rows.filter(({ record }) => {
  const nodeMatches = props.nodes.length === 0 || props.nodes.includes(record.currentNodeCode ?? '')
  const planMatches = props.plans.length === 0 || props.plans.includes(record.compensationPlan ?? '')
  return nodeMatches && planMatches
}))

function visibleActions(aggregate: Phase10Aggregate): readonly Phase10Action[] {
  const actions = props.actionsFor(aggregate)
  return props.actionCodes.length === 0
    ? actions
    : actions.filter(({ code }) => props.actionCodes.includes(code))
}
</script>

<template>
  <SgjTable
    caption="加班与调休业务记录"
    :empty="visibleRows.length === 0"
    :empty-text="emptyText"
    :column-count="8"
  >
    <template #head>
      <tr><th>业务编号</th><th>任务</th><th>计划区间</th><th>实际工时</th><th>补偿方案</th><th>节点</th><th>状态</th><th>动作</th></tr>
    </template>
    <template #body>
      <tr v-for="aggregate in visibleRows" :key="aggregate.record.id" :data-record-id="aggregate.record.id">
        <td>{{ aggregate.record.businessNo }}</td>
        <td>{{ aggregate.record.subject || '受限元数据' }}</td>
        <td>{{ displayTime(aggregate.record.startAt) }}<br>{{ displayTime(aggregate.record.endAt) }}</td>
        <td>{{ displayNumber(aggregate.record.actualDurationHours, ' 小时') }}</td>
        <td>{{ aggregate.record.compensationPlan || '-' }}</td>
        <td>{{ aggregate.record.currentNodeCode || '-' }}</td>
        <td>
          <SgjStatusChip :tone="statusTone(aggregate.record.status)">
            {{ aggregate.record.status }}
          </SgjStatusChip>
        </td>
        <td>
          <div class="phase10-inline-actions">
            <SgjButton
              v-for="action in visibleActions(aggregate)"
              :key="action.code"
              :variant="action.tone || 'primary'"
              size="sm"
              :disabled="busy || !canAct(action.code)"
              @click="emit('act', aggregate, action.code)"
            >
              {{ action.label }}
            </SgjButton>
          </div>
        </td>
      </tr>
    </template>
  </SgjTable>
</template>
