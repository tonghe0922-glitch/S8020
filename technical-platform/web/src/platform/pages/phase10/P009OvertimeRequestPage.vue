<script setup lang="ts">
import { SgjFormPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import OvertimeRequestForm from '../../phase10/p009/components/OvertimeRequestForm.vue'
import { useOvertimeOperations } from '../../phase10/p009/useOvertimeOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  form, busy, feedback, failed, total, open, closed, canSubmit, create,
} = useOvertimeOperations()
</script>

<template>
  <SgjFormPageTemplate
    title="加班申请"
    description="登记计划劳动区间、任务必要性与紧急事实；审批不能替代实际劳动记录。"
    data-testid="p009-overtime-request-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <OvertimeRequestForm
      v-model:subject="form.subject"
      v-model:reason="form.reason"
      v-model:attendance-type="form.attendanceType"
      v-model:start-at="form.startAt"
      v-model:end-at="form.endAt"
      v-model:emergency-fact="form.emergencyFact"
      :disabled="busy || !canSubmit"
      @submit="create"
    />
    <template #aside>
      <div class="phase10-stack">
        <h2>我的加班概览</h2>
        <Phase10Kpis :total="total" :open="open" :closed="closed" />
        <p class="phase10-note">服务端会校验与请假、排班及其他有效劳动事实的时间冲突。</p>
      </div>
    </template>
  </SgjFormPageTemplate>
</template>
