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
const actionCodes = ['HR_REVIEW'] as const
</script>

<template>
  <SgjApprovalPageTemplate
    title="加班人事复核"
    description="复核审批、实际考勤和成果验收证据，为补偿方案提供合规依据。"
    data-testid="p009-hr-review-page"
  >
    <template #context>
      <Phase10Feedback :message="feedback" :failed="failed" />
      <p class="phase10-note">人事复核通过后才可进入法定工资或调休方案节点。</p>
    </template>
    <OvertimeRecordsTable
      :rows="rows"
      :nodes="['S06']"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待人事复核记录"
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
      />
    </template>
  </SgjApprovalPageTemplate>
</template>
