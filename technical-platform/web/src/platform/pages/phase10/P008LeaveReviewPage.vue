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
const actionCodes = ['APPROVE_LEAVE', 'REJECT_LEAVE'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="请假审批"
    description="中心审批人核对休假安排与交接事实后，执行批准或驳回。"
    data-testid="p008-leave-review-page"
  >
    <template #context>
      <Phase10Feedback :message="feedback" :failed="failed" />
      <p class="phase10-note">批准不会直接绕过额度结算；后续仍由 S05 将预占转为扣减。</p>
    </template>
    <LeaveRecordsTable
      :rows="rows"
      :nodes="['S04']"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待审批请假申请"
      @act="act"
    />
    <template #decision>
      <LeaveActionFields
        v-model:reason="form.reason"
        v-model:actual-at="form.actualAt"
        v-model:adjustment-amount="form.adjustmentAmount"
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
