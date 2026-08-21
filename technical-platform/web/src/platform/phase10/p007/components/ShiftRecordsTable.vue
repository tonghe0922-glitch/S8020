<script setup lang="ts">
import { computed } from 'vue'
import { SgjButton, SgjInput, SgjStatusChip, SgjTable } from '../../../../design-system'
import { displayTime, statusTone } from '../../shared'
import { SHIFT_ACTIONS } from '../useShiftOperations'
import type { ShiftAggregate } from '../types'

const props = defineProps<{
  rows: readonly ShiftAggregate[]
  center: boolean
  busy: boolean
  canChange: boolean
  canAct: (action: string) => boolean
}>()
const replacement = defineModel<string>('replacement', { required: true })
const reason = defineModel<string>('reason', { required: true })
const emit = defineEmits<{ act: [aggregate: ShiftAggregate, action: string] }>()

const columnCount = computed(() => props.center ? 8 : 7)

function integrationText(aggregate: ShiftAggregate): string {
  const record = aggregate.record
  if (!record.attendanceLinkedAt) return '-'
  return [
    `考勤 ${displayTime(record.attendanceLinkedAt)}`,
    `餐饮 ${displayTime(record.cateringLinkedAt)}`,
    `班车 ${displayTime(record.shuttleLinkedAt)}`,
  ].join(' · ')
}
</script>

<template>
  <SgjTable
    caption="排班与班次调整记录"
    :empty="rows.length === 0"
    empty-text="暂无排班或换班记录"
    :column-count="columnCount"
  >
    <template #head>
      <tr>
        <th>业务编号</th><th>排班时段</th><th>校验事实</th><th>审批事实</th>
        <th>联动事实</th><th>状态</th><th>动作</th><th v-if="center">处理说明</th>
      </tr>
    </template>
    <template #body>
      <tr v-for="aggregate in rows" :key="aggregate.record.id" :data-shift-id="aggregate.record.id">
        <td>
          {{ aggregate.record.businessNo }}<br>
          {{ aggregate.record.subject || '排班运行元数据' }}
        </td>
        <td>
          {{ displayTime(aggregate.record.startAt) }} → {{ displayTime(aggregate.record.endAt) }}<br>
          {{ aggregate.record.durationHours }} 小时
        </td>
        <td>
          资格 {{ displayTime(aggregate.record.qualificationCheckedAt) }}<br>
          冲突 {{ displayTime(aggregate.record.conflictCheckedAt) }}
        </td>
        <td>{{ displayTime(aggregate.record.approvedAt) }}</td>
        <td>{{ integrationText(aggregate) }}</td>
        <td>
          {{ aggregate.record.currentNodeCode || '-' }}<br>
          <SgjStatusChip :tone="statusTone(aggregate.record.status)">
            {{ aggregate.record.status }}
          </SgjStatusChip>
        </td>
        <td>
          <div class="phase10-inline-actions">
            <SgjButton
              v-if="!center && aggregate.record.currentNodeCode === 'S05'"
              size="sm"
              :disabled="busy || !canChange"
              @click="emit('act', aggregate, 'CONFIRM_SCHEDULE')"
            >
              确认排班
            </SgjButton>
            <template v-if="!center && aggregate.record.currentNodeCode === 'S06'">
              <SgjInput v-model="replacement" label="替班员工 UUID（可选）" />
              <SgjButton
                size="sm"
                :disabled="busy || !canChange"
                @click="emit('act', aggregate, 'SUBMIT_SHIFT_CHANGE')"
              >
                提交换班/替班申请
              </SgjButton>
            </template>
            <SgjButton
              v-for="action in center ? SHIFT_ACTIONS[aggregate.record.currentNodeCode || ''] || [] : []"
              :key="action.code"
              size="sm"
              :disabled="busy || !canAct(action.code)"
              @click="emit('act', aggregate, action.code)"
            >
              {{ action.label }}
            </SgjButton>
          </div>
        </td>
        <td v-if="center"><SgjInput v-model="reason" label="处理说明" /></td>
      </tr>
    </template>
  </SgjTable>
</template>
