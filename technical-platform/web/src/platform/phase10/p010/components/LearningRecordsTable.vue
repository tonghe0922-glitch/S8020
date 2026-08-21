<script setup lang="ts">
import { computed } from 'vue'
import { SgjButton, SgjStatusChip, SgjTable } from '../../../../design-system'
import { displayNumber, statusTone } from '../../shared'
import type { Phase10Action, Phase10Aggregate } from '../../shared'

type LearningActivity = 'learning-progress' | 'exam' | 'practical'

const props = withDefaults(defineProps<{
  rows: readonly Phase10Aggregate[]
  nodes?: readonly string[]
  actionCodes?: readonly string[]
  activity?: LearningActivity | null
  busy: boolean
  actionsFor: (aggregate: Phase10Aggregate) => readonly Phase10Action[]
  canAct: (action: string) => boolean
  canActivity?: boolean
  emptyText?: string
}>(), {
  nodes: () => [],
  actionCodes: () => [],
  activity: null,
  canActivity: false,
  emptyText: '当前没有符合条件的学习记录',
})

const emit = defineEmits<{
  act: [aggregate: Phase10Aggregate, action: string]
  activity: [aggregate: Phase10Aggregate, activity: LearningActivity]
}>()
const visibleRows = computed(() => props.nodes.length === 0
  ? props.rows
  : props.rows.filter(({ record }) => props.nodes.includes(record.currentNodeCode ?? '')))

function visibleActions(aggregate: Phase10Aggregate): readonly Phase10Action[] {
  const actions = props.actionsFor(aggregate)
  return props.actionCodes.length === 0
    ? actions
    : actions.filter(({ code }) => props.actionCodes.includes(code))
}

function activityLabel(activity: LearningActivity): string {
  if (activity === 'learning-progress') return '提交学习进度'
  if (activity === 'exam') return '提交考试成绩'
  return '提交实操结果'
}
</script>

<template>
  <SgjTable
    caption="学习、考试与资格记录"
    :empty="visibleRows.length === 0"
    :empty-text="emptyText"
    :column-count="9"
  >
    <template #head>
      <tr>
        <th>业务编号</th><th>任务/课程</th><th>完成率</th><th>成绩</th><th>实操</th>
        <th>资格有效期</th><th>节点</th><th>状态</th><th>动作</th>
      </tr>
    </template>
    <template #body>
      <tr v-for="aggregate in visibleRows" :key="aggregate.record.id" :data-record-id="aggregate.record.id">
        <td>{{ aggregate.record.businessNo }}</td>
        <td>{{ aggregate.record.subject || '受限元数据' }}<br>{{ aggregate.record.periodOrCourseNo || '-' }}</td>
        <td>{{ displayNumber(aggregate.record.completionRate, '%') }}</td>
        <td>{{ displayNumber(aggregate.record.score1000, ' / 1000') }}</td>
        <td>{{ aggregate.record.practicalResult || '-' }}</td>
        <td>
          {{ aggregate.record.qualificationEffectiveDate || '-' }}<br>
          {{ aggregate.record.qualificationExpireDate || '-' }}
        </td>
        <td>{{ aggregate.record.currentNodeCode || '-' }}</td>
        <td>
          <SgjStatusChip :tone="statusTone(aggregate.record.status)">
            {{ aggregate.record.status }}
          </SgjStatusChip>
        </td>
        <td>
          <div class="phase10-inline-actions">
            <SgjButton
              v-if="activity"
              size="sm"
              :disabled="busy || !canActivity"
              @click="emit('activity', aggregate, activity)"
            >
              {{ activityLabel(activity) }}
            </SgjButton>
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
