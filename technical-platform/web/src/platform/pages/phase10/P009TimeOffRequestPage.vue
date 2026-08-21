<script setup lang="ts">
import { SgjButton, SgjListPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import OvertimeRecordsTable from '../../phase10/p009/components/OvertimeRecordsTable.vue'
import { useOvertimeOperations } from '../../phase10/p009/useOvertimeOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, busy, feedback, failed, actionsFor, canAct, load,
} = useOvertimeOperations()
const plans = ['TIME_OFF', 'MIXED'] as const
</script>

<template>
  <SgjListPageTemplate
    title="调休申请与兑现状态"
    description="查看经人事复核后形成的调休额度及其后续薪酬回执状态。"
    data-testid="p009-time-off-request-page"
  >
    <template #actions>
      <SgjButton variant="secondary" size="sm" :disabled="busy" @click="load">
        刷新状态
      </SgjButton>
    </template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <p class="phase10-note">P009 的调休额度只能由已核验的实际加班事实生成，员工端不能自行写入额度账本。</p>
    <OvertimeRecordsTable
      :rows="rows"
      :plans="plans"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无已形成调休额度的加班记录"
    />
  </SgjListPageTemplate>
</template>
