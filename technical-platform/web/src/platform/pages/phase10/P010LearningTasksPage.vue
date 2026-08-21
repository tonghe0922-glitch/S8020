<script setup lang="ts">
import { SgjListPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LearningActivityFields from '../../phase10/p010/components/LearningActivityFields.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, canComplete, activity,
} = useLearningOperations()
const nodes = ['S03'] as const
</script>

<template>
  <SgjListPageTemplate
    title="我的学习任务"
    description="按岗位风险完成已指派课程，并提交可审计的学习进度证据。"
    data-testid="p010-learning-tasks-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LearningActivityFields
      v-model:completion-rate="form.completionRate"
      v-model:score1000="form.score1000"
      v-model:practical-result="form.practicalResult"
      v-model:note="form.note"
      v-model:effective-date="form.effectiveDate"
      v-model:expire-date="form.expireDate"
      show-progress
    />
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      activity="learning-progress"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      :can-activity="canComplete"
      empty-text="暂无待完成的学习任务"
      @activity="activity"
    />
  </SgjListPageTemplate>
</template>
