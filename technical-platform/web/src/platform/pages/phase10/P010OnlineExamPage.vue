<script setup lang="ts">
import { SgjFormPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LearningActivityFields from '../../phase10/p010/components/LearningActivityFields.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, canExam, activity,
} = useLearningOperations()
const nodes = ['S04'] as const
</script>

<template>
  <SgjFormPageTemplate
    title="在线考试"
    description="考试成绩使用统一 1000 分制，服务端保留题目版本、作答与评分证据。"
    data-testid="p010-online-exam-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LearningActivityFields
      v-model:completion-rate="form.completionRate"
      v-model:score1000="form.score1000"
      v-model:practical-result="form.practicalResult"
      v-model:note="form.note"
      v-model:effective-date="form.effectiveDate"
      v-model:expire-date="form.expireDate"
      show-exam
    />
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      activity="exam"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      :can-activity="canExam"
      empty-text="暂无待参加的在线考试"
      @activity="activity"
    />
  </SgjFormPageTemplate>
</template>
