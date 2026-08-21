<script setup lang="ts">
import { SgjApprovalPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LearningActivityFields from '../../phase10/p010/components/LearningActivityFields.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, act,
} = useLearningOperations()
const nodes = ['S06'] as const
const actionCodes = ['CERTIFY', 'RETURN_FOR_TRAINING'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="实操认证"
    description="认证人员依据考试与实操证据作出通过或补训结论，保留原始证据链。"
    data-testid="p010-practical-certification-page"
  >
    <template #context><Phase10Feedback :message="feedback" :failed="failed" /></template>
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待认证实操任务"
      @act="act"
    />
    <template #decision>
      <LearningActivityFields
        v-model:completion-rate="form.completionRate"
        v-model:score1000="form.score1000"
        v-model:practical-result="form.practicalResult"
        v-model:note="form.note"
        v-model:effective-date="form.effectiveDate"
        v-model:expire-date="form.expireDate"
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
