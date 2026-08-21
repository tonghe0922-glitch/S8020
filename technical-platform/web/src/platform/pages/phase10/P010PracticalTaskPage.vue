<script setup lang="ts">
import { SgjFormPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LearningActivityFields from '../../phase10/p010/components/LearningActivityFields.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, canComplete, activity,
} = useLearningOperations()
const nodes = ['S05'] as const
</script>

<template>
  <SgjFormPageTemplate
    title="实操任务"
    description="提交现场实操结果，后续由有资格的认证人员独立审核。"
    data-testid="p010-practical-task-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LearningActivityFields
      v-model:completion-rate="form.completionRate"
      v-model:score1000="form.score1000"
      v-model:practical-result="form.practicalResult"
      v-model:note="form.note"
      v-model:effective-date="form.effectiveDate"
      v-model:expire-date="form.expireDate"
      show-practical
    />
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      activity="practical"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      :can-activity="canComplete"
      empty-text="暂无待完成的实操任务"
      @activity="activity"
    />
  </SgjFormPageTemplate>
</template>
