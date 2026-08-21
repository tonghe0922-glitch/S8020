<script setup lang="ts">
import { SgjButton, SgjDashboardPageTemplate } from '../../design-system'
import Phase10Feedback from '../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../phase10/components/Phase10Kpis.vue'
import ShiftRecordsTable from '../phase10/p007/components/ShiftRecordsTable.vue'
import ShiftRequestForm from '../phase10/p007/components/ShiftRequestForm.vue'
import { useShiftOperations } from '../phase10/p007/useShiftOperations'
import type { PortalDefinition } from '../portal-config'

const props = defineProps<{ portal: PortalDefinition; mode: 'employee' | 'center' }>()
const {
  rows, form, busy, feedback, failed, load, create, act, center,
  canManage, canChange, canAct, total, open, closed,
} = useShiftOperations(props.mode)
</script>

<template>
  <SgjDashboardPageTemplate
    :title="center ? '排班与班次调整管理' : '我的排班与换班'"
    description="排班、资格校验、换班审批和依赖联动均由服务端状态机与 PostgreSQL 事实驱动。"
    data-testid="p007-page"
  >
    <template #actions>
      <SgjButton variant="secondary" size="sm" :loading="busy" @click="load">刷新</SgjButton>
    </template>
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <ShiftRequestForm
      v-model:subject="form.subject"
      v-model:change-reason="form.changeReason"
      v-model:template-code="form.templateCode"
      v-model:period="form.period"
      v-model:target-employee="form.targetEmployee"
      v-model:start-at="form.startAt"
      v-model:end-at="form.endAt"
      :center="center"
      :disabled="busy || (center ? !canManage : !canChange)"
      @submit="create"
    />
    <ShiftRecordsTable
      v-model:replacement="form.replacement"
      v-model:reason="form.reason"
      :rows="rows"
      :center="center"
      :busy="busy"
      :can-change="canChange"
      :can-act="canAct"
      @act="act"
    />
  </SgjDashboardPageTemplate>
</template>
