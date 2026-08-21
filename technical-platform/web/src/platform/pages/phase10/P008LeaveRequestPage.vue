<script setup lang="ts">
import { SgjFormPageTemplate } from '../../../design-system'
import LeaveRequestForm from '../../phase10/p008/components/LeaveRequestForm.vue'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../../phase10/components/Phase10Kpis.vue'
import { useLeaveOperations } from '../../phase10/p008/useLeaveOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const {
  form, busy, feedback, failed, total, open, closed, canSubmit, create,
} = useLeaveOperations()
</script>

<template>
  <SgjFormPageTemplate
    title="请假申请"
    description="提交请假区间、额度账户与工作交接信息；额度是否可用由服务端校验。"
    data-testid="p008-leave-request-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LeaveRequestForm
      v-model:subject="form.subject"
      v-model:reason="form.reason"
      v-model:attendance-type="form.attendanceType"
      v-model:start-at="form.startAt"
      v-model:end-at="form.endAt"
      v-model:quota-account-id="form.quotaAccountId"
      v-model:quota-amount="form.quotaAmount"
      v-model:handover-agent-id="form.handoverAgentId"
      v-model:known-impact="form.knownImpact"
      :disabled="busy || !canSubmit"
      @submit="create"
    />
    <template #aside>
      <div class="phase10-stack">
        <h2>我的请假概览</h2>
        <Phase10Kpis :total="total" :open="open" :closed="closed" />
        <p class="phase10-note">页面不预判额度、冲突或审批结果，所有事实以 PostgreSQL 状态机为准。</p>
      </div>
    </template>
  </SgjFormPageTemplate>
</template>
