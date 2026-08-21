<script setup lang="ts">
import { SgjDashboardPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import OvertimeActionFields from '../../phase10/p009/components/OvertimeActionFields.vue'
import OvertimeRecordsTable from '../../phase10/p009/components/OvertimeRecordsTable.vue'
import { useOvertimeOperations } from '../../phase10/p009/useOvertimeOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, form, busy, feedback, failed, total, open, closed, actionsFor, canAct, act,
} = useOvertimeOperations()
const nodes = ['S07', 'S08', 'S09'] as const
const actionCodes = ['SET_COMPENSATION_PLAN', 'ACK_PAYROLL_RECEIPT', 'ARCHIVE'] as const
</script>

<template>
  <SgjDashboardPageTemplate
    title="薪酬与调休依据"
    description="依据已核验劳动事实确定工资、调休或组合方案，并登记薪酬回执。"
    data-testid="p009-payroll-basis-page"
  >
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <OvertimeRecordsTable
      :rows="rows"
      :nodes="nodes"
      :action-codes="actionCodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无待生成薪酬或调休依据的记录"
      @act="act"
    />
    <template #aside>
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
        show-compensation
        show-payroll
      />
    </template>
  </SgjDashboardPageTemplate>
</template>
