<script setup lang="ts">
import { SgjButton, SgjDashboardPageTemplate } from '../../design-system'
import Phase10Feedback from '../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../phase10/components/Phase10Kpis.vue'
import MeetingCreateForm from '../phase10/p006/components/MeetingCreateForm.vue'
import MeetingRecordCard from '../phase10/p006/components/MeetingRecordCard.vue'
import { useMeetingOperations } from '../phase10/p006/useMeetingOperations'
import type { PortalDefinition } from '../portal-config'

const props = defineProps<{ portal: PortalDefinition; mode: 'employee' | 'center' }>()
const {
  rows, form, busy, feedback, failed, load, create, manage, attendance, evidence, rework,
  isCenter, employeeId, canCreate, canManage, canAccept, canAction, total, open, closed,
} = useMeetingOperations(props.mode)
</script>

<template>
  <SgjDashboardPageTemplate
    :title="isCenter ? '会议与行动项管理' : '我的会议与行动项'"
    description="会议纪要、行动项、验收与返工均来自服务端 canonical meeting / meeting_item。"
    data-testid="p006-page"
  >
    <template #actions>
      <SgjButton variant="secondary" size="sm" :loading="busy" @click="load">刷新</SgjButton>
    </template>
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <MeetingCreateForm
      v-if="isCenter"
      v-model:subject="form.subject"
      v-model:content="form.content"
      v-model:venue="form.venue"
      v-model:start-at="form.startAt"
      v-model:visibility="form.visibility"
      v-model:attendance-type="form.attendanceType"
      v-model:participants="form.participants"
      v-model:agenda="form.agenda"
      :disabled="busy || !canCreate"
      @submit="create"
    />
    <div class="phase10-card-stack">
      <MeetingRecordCard
        v-for="aggregate in rows"
        :key="aggregate.meeting.id"
        v-model:minutes="form.minutes"
        v-model:action-title="form.actionTitle"
        v-model:action-owner="form.actionOwner"
        v-model:action-due-at="form.actionDueAt"
        v-model:action-evidence="form.actionEvidence"
        v-model:reason="form.reason"
        :aggregate="aggregate"
        :employee-id="employeeId"
        :center="isCenter"
        :busy="busy"
        :can-manage="canManage"
        :can-accept="canAccept"
        :can-action="canAction"
        @manage="manage"
        @attendance="attendance"
        @evidence="evidence"
        @rework="rework"
      />
    </div>
  </SgjDashboardPageTemplate>
</template>
