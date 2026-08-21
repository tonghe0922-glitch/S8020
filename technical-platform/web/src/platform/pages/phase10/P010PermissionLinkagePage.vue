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
const nodes = ['S07', 'S08'] as const
const actionCodes = ['ACTIVATE_QUALIFICATION', 'LINK_PERMISSIONS'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="资格生效与权限联动"
    description="先校验资格有效期，再由服务端按岗位权限策略执行授予或回收。"
    data-testid="p010-permission-linkage-page"
  >
    <template #context><Phase10Feedback :message="feedback" :failed="failed" /></template>
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待生效或联动的资格"
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
        show-dates
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
