<script setup lang="ts">
import { SgjDashboardPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import LearningRecordsTable from '../../phase10/p010/components/LearningRecordsTable.vue'
import { useLearningOperations } from '../../phase10/p010/useLearningOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  rows, busy, feedback, failed, total, open, closed, actionsFor, canAct,
} = useLearningOperations()
const nodes = ['S06', 'S07', 'S08', 'S09', 'S10', 'END'] as const
</script>

<template>
  <SgjDashboardPageTemplate
    title="我的资格"
    description="查看资格认证、生效、权限联动、复证检查和归档状态。"
    data-testid="p010-qualifications-page"
  >
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LearningRecordsTable
      :rows="rows"
      :nodes="nodes"
      :busy="busy"
      :actions-for="actionsFor"
      :can-act="canAct"
      empty-text="暂无资格记录"
    />
  </SgjDashboardPageTemplate>
</template>
