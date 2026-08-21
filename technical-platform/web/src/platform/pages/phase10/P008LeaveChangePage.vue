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
const actionCodes = ['RETURN_TO_WORK', 'CHANGE_LEAVE'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="销假与休假变更"
    description="仅对已开始休假的记录提交返岗或变更动作，并携带当前版本号。"
    data-testid="p008-leave-change-page"
  >
    <template #context>
      <Phase10Feedback :message="feedback" :failed="failed" />
      <p class="phase10-note">提前返岗时间不得早于实际休假开始时间，服务端会执行时序校验。</p>
    </template>
    <LeaveRecordsTable
      :rows="rows"
      :nodes="['S08']"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="没有待销假或待变更的记录"
      @act="act"
    />
    <template #decision>
      <LeaveActionFields
        v-model:reason="form.reason"
        v-model:actual-at="form.actualAt"
        v-model:adjustment-amount="form.adjustmentAmount"
        show-actual-at
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
