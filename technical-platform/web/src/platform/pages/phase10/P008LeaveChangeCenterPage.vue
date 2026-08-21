<script setup lang="ts">
import { SgjApprovalPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LeaveActionFields from '../../phase10/p008/components/LeaveActionFields.vue'
import LeaveRecordsTable from '../../phase10/p008/components/LeaveRecordsTable.vue'
import { useLeaveOperations } from '../../phase10/p008/useLeaveOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, act,
} = useLeaveOperations()
const nodes = ['S08', 'S09'] as const
const actionCodes = ['RETURN_TO_WORK', 'CHANGE_LEAVE', 'ADJUST_QUOTA'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="请假变更审批与差额处理"
    description="中心端复核返岗/变更事实，并在需要时登记额度差额。"
    data-testid="p008-leave-change-center-page"
  >
    <template #context><Phase10Feedback :message="feedback" :failed="failed" /></template>
    <LeaveRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无请假变更或差额事项"
      @act="act"
    />
    <template #decision>
      <LeaveActionFields
        v-model:reason="form.reason"
        v-model:actual-at="form.actualAt"
        v-model:adjustment-amount="form.adjustmentAmount"
        show-actual-at
        show-adjustment
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
