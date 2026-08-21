<script setup lang="ts">
import { SgjDashboardPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import LearningAssignmentForm from '../../phase10/p010/components/LearningAssignmentForm.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, total, open, closed, create,
  actionsFor, canAct, canManage, act,
} = useLearningOperations()
const nodes = ['S01', 'S02', 'S09', 'S10'] as const
const actionCodes = [
  'PUBLISH_CONTENT', 'ASSIGN_BY_RISK', 'COMPLETE_RETRAINING_CHECK', 'ARCHIVE',
] as const
</script>

<template>
  <SgjDashboardPageTemplate
    title="学习管理"
    description="发布受控内容版本、按风险指派课程，并管理复训与归档。"
    data-testid="p010-learning-management-page"
  >
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LearningAssignmentForm
      v-model:subject="form.subject"
      v-model:reason="form.reason"
      v-model:owner-employee-id="form.ownerEmployeeId"
      v-model:content-version="form.contentVersion"
      v-model:course-team-name="form.courseTeamName"
      v-model:course-version-id="form.courseVersionId"
      v-model:learner-profile="form.learnerProfile"
      v-model:period-or-course-no="form.periodOrCourseNo"
      v-model:risk-level="form.riskLevel"
      v-model:planned-start-at="form.plannedStartAt"
      v-model:planned-finish-at="form.plannedFinishAt"
      :disabled="busy || !canManage"
      @submit="create"
    />
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待管理学习任务"
      @act="act"
    />
  </SgjDashboardPageTemplate>
</template>
