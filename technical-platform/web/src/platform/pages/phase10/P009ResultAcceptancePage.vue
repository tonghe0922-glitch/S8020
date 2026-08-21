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
const nodes = ['S04', 'S05'] as const
const actionCodes = ['RECORD_ACTUAL_FACT'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="实际劳动与成果状态"
    description="员工登记实际劳动区间并查看成果验收进度；验收动作由授权管理人执行。"
    data-testid="p009-result-acceptance-page"
  >
    <template #context>
      <Phase10Feedback :message="feedback" :failed="failed" />
      <p class="phase10-note">实际劳动事实必须与考勤一致，且不得与有效请假区间重叠。</p>
    </template>
    <OvertimeRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待登记实际劳动或待验收记录"
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
        show-actual
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
