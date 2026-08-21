<script setup lang="ts">
import { SgjApprovalPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import OvertimeActionFields from '../../phase10/p009/components/OvertimeActionFields.vue'
import OvertimeRecordsTable from '../../phase10/p009/components/OvertimeRecordsTable.vue'
import { useOvertimeOperations } from '../../phase10/p009/useOvertimeOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, actionsFor, canAct, act,
} = useOvertimeOperations()
const nodes = ['S02', 'S03', 'S05'] as const
const actionCodes = ['VALIDATE_NECESSITY', 'APPROVE_OVERTIME', 'REJECT_OVERTIME', 'ACCEPT_RESULT'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="加班管理与成果验收"
    description="完成必要性校验、主管审批和成果验收，不替代员工实际劳动事实。"
    data-testid="p009-overtime-management-page"
  >
    <template #context><Phase10Feedback :message="feedback" :failed="failed" /></template>
    <OvertimeRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待管理加班事项"
      @act="act"
    />
    <template #decision>
      <OvertimeActionFields
        v-model:reason="form.reason"
        v-model:actual-start-at="form.actualStartAt"
        v-model:actual-end-at="form.actualEndAt"
        v-model:attendance-summary="form.attendanceSummary"
        v-model:result-summary="form.resultSummary"
        v-model:compensation-plan="form.compensationPlan"
        v-model:wage-amount="form.wageAmount"
        v-model:quota-account-id="form.quotaAccountId"
        v-model:time-off-hours="form.timeOffHours"
        v-model:payroll-reference="form.payrollReference"
        show-result
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
