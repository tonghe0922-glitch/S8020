<script setup lang="ts">
import { SgjDashboardPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import LeaveActionFields from '../../phase10/p008/components/LeaveActionFields.vue'
import LeaveLedgerTable from '../../phase10/p008/components/LeaveLedgerTable.vue'
import LeaveRecordsTable from '../../phase10/p008/components/LeaveRecordsTable.vue'
import { useLeaveOperations } from '../../phase10/p008/useLeaveOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, ledger, form, busy, feedback, failed, total, open, closed,
  actionsFor, canAct, act, load,
} = useLeaveOperations()
const nodes = ['S02', 'S05', 'S09', 'S10'] as const
</script>

<template>
  <SgjDashboardPageTemplate
    title="额度预占与结算管理"
    description="处理额度预占、扣减/释放、差额调整与日结归档。"
    data-testid="p008-quota-management-page"
  >
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <div class="phase10-stack">
      <LeaveRecordsTable
        :rows="rows"
        :nodes="nodes"
        :busy="busy"
        :actions-for="actionsFor"
        :can-act="canAct"
        empty-text="暂无待处理额度事项"
        @act="act"
      />
      <LeaveLedgerTable :entries="ledger" :busy="busy" @refresh="load" />
    </div>
    <template #aside>
      <LeaveActionFields
        v-model:reason="form.reason"
        v-model:actual-at="form.actualAt"
        v-model:adjustment-amount="form.adjustmentAmount"
        show-adjustment
      />
    </template>
  </SgjDashboardPageTemplate>
</template>
