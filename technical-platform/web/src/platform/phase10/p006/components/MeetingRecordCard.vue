<script setup lang="ts">
import { computed } from 'vue'
import {
  SgjButton,
  SgjCard,
  SgjDateTime,
  SgjInput,
  SgjStatusChip,
  SgjTextarea,
} from '../../../../design-system'
import { displayTime, statusTone } from '../../shared'
import { MEETING_ACTIONS } from '../useMeetingOperations'
import type { MeetingAggregate, MeetingItem } from '../types'

const props = defineProps<{
  aggregate: MeetingAggregate
  employeeId: string
  center: boolean
  busy: boolean
  canManage: boolean
  canAccept: boolean
  canAction: boolean
}>()
const minutes = defineModel<string>('minutes', { required: true })
const actionTitle = defineModel<string>('actionTitle', { required: true })
const actionOwner = defineModel<string>('actionOwner', { required: true })
const actionDueAt = defineModel<string>('actionDueAt', { required: true })
const actionEvidence = defineModel<string>('actionEvidence', { required: true })
const reason = defineModel<string>('reason', { required: true })
const emit = defineEmits<{
  manage: [aggregate: MeetingAggregate, action: string]
  attendance: [aggregate: MeetingAggregate, action: string]
  evidence: [aggregate: MeetingAggregate, item: MeetingItem]
  rework: [aggregate: MeetingAggregate, item: MeetingItem]
}>()

const meeting = computed(() => props.aggregate.meeting)
const node = computed(() => meeting.value.currentNodeCode ?? '')
const agendaItems = computed(() => itemType('AGENDA'))
const actionItems = computed(() => itemType('ACTION_ITEM'))
const participant = computed(() => itemType('PARTICIPANT').find(
  ({ relatedObjectId }) => relatedObjectId === props.employeeId,
))

function itemType(type: string): MeetingItem[] {
  return props.aggregate.items.filter(({ fieldCode }) => fieldCode === type)
}

function ownsAction(item: MeetingItem): boolean {
  return item.actionOwnerEmployeeId === props.employeeId
}

function canCenterAction(code: string): boolean {
  return code === 'ACCEPT_ACTIONS' ? props.canAccept : props.canManage
}
</script>

<template>
  <SgjCard as="article" :data-meeting-id="meeting.id">
    <template #header>
      <div>
        <h2>{{ meeting.businessNo }} · {{ meeting.officialSubject || '会议运行元数据' }}</h2>
        <p>
          节点 {{ meeting.currentNodeCode || '-' }} ·
          <SgjStatusChip :tone="statusTone(meeting.status)">{{ meeting.status }}</SgjStatusChip>
          · version {{ meeting.versionNo }} · {{ displayTime(meeting.startAt) }}
        </p>
      </div>
    </template>
    <p v-if="meeting.officialContent">{{ meeting.officialContent }}</p>
    <div v-if="agendaItems.length">
      <strong>议题</strong>
      <ul><li v-for="item in agendaItems" :key="item.id">{{ item.itemName }}</li></ul>
    </div>
    <div v-if="!center && node === 'S04' && canAction && participant?.actionStatus === 'PENDING'">
      <div class="phase10-inline-actions">
        <SgjButton size="sm" :disabled="busy" @click="emit('attendance', aggregate, 'ATTEND')">
          签到
        </SgjButton>
        <SgjButton
          variant="secondary"
          size="sm"
          :disabled="busy"
          @click="emit('attendance', aggregate, 'LEAVE')"
        >
          请假
        </SgjButton>
      </div>
    </div>
    <SgjTextarea v-if="center && node === 'S06'" v-model="minutes" label="会议纪要" />
    <div v-if="center && node === 'S07'" class="phase10-form-grid">
      <SgjInput v-model="actionTitle" label="行动项" />
      <SgjInput v-model="actionOwner" label="责任人 UUID" />
      <SgjDateTime v-model="actionDueAt" label="计划完成时间" mode="datetime" />
    </div>
    <div v-if="actionItems.length" class="phase10-card-stack">
      <strong>行动项</strong>
      <SgjCard v-for="item in actionItems" :key="item.id" as="article" variant="muted">
        <p>
          {{ item.itemName }} · {{ item.actionStatus }} ·
          截止 {{ displayTime(item.actionDueAt) }} · 返工 {{ item.reworkCount }} 次
        </p>
        <p v-if="item.executionEvidence">证据：{{ item.executionEvidence }}</p>
        <template v-if="!center && node === 'S08' && canAction && ownsAction(item)">
          <SgjInput v-model="actionEvidence" label="执行证据/结果摘要" />
          <SgjButton size="sm" :disabled="busy" @click="emit('evidence', aggregate, item)">
            提交执行证据
          </SgjButton>
        </template>
        <SgjButton
          v-if="center && node === 'S09' && canAccept && item.actionStatus === 'EXECUTED'"
          variant="danger"
          size="sm"
          :disabled="busy"
          @click="emit('rework', aggregate, item)"
        >
          退回返工
        </SgjButton>
      </SgjCard>
    </div>
    <template #footer>
      <div v-if="center" class="phase10-form">
        <SgjInput v-model="reason" label="处理说明" />
        <div class="phase10-inline-actions">
          <SgjButton
            v-for="action in MEETING_ACTIONS[node] || []"
            :key="action.code"
            size="sm"
            :disabled="busy || !canCenterAction(action.code)"
            @click="emit('manage', aggregate, action.code)"
          >
            {{ action.label }}
          </SgjButton>
        </div>
      </div>
    </template>
  </SgjCard>
</template>
