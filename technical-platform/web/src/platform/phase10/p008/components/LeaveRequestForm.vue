<script setup lang="ts">
import {
  SgjButton,
  SgjDateTime,
  SgjInput,
  SgjSelect,
  SgjTextarea,
  type SelectOption,
} from '../../../../design-system'

const subject = defineModel<string>('subject', { required: true })
const reason = defineModel<string>('reason', { required: true })
const attendanceType = defineModel<string>('attendanceType', { required: true })
const startAt = defineModel<string>('startAt', { required: true })
const endAt = defineModel<string>('endAt', { required: true })
const quotaAccountId = defineModel<string>('quotaAccountId', { required: true })
const quotaAmount = defineModel<string>('quotaAmount', { required: true })
const handoverAgentId = defineModel<string>('handoverAgentId', { required: true })
const knownImpact = defineModel<string>('knownImpact', { required: true })

defineProps<{ disabled: boolean }>()
defineEmits<{ submit: [] }>()

const leaveTypes: readonly SelectOption[] = [
  { value: 'ANNUAL_LEAVE', label: '年休假' },
  { value: 'PERSONAL_LEAVE', label: '事假' },
  { value: 'SICK_LEAVE', label: '病假' },
  { value: 'COMPENSATORY_LEAVE', label: '调休' },
]
</script>

<template>
  <form class="phase10-form" @submit.prevent="$emit('submit')">
    <div class="phase10-form-grid">
      <SgjInput v-model="subject" label="申请主题" required placeholder="例如：家庭事务请假" />
      <SgjSelect v-model="attendanceType" label="请假类型" required :options="leaveTypes" />
      <SgjDateTime v-model="startAt" label="开始时间" mode="datetime" required />
      <SgjDateTime v-model="endAt" label="结束时间" mode="datetime" required />
      <SgjInput v-model="quotaAccountId" label="额度账户" required placeholder="ANNUAL" />
      <SgjInput v-model="quotaAmount" label="申请额度" type="number" min="0" step="0.5" required />
      <SgjInput v-model="handoverAgentId" label="工作代理人 UUID" placeholder="可选" />
    </div>
    <SgjTextarea v-model="reason" label="请假原因" required />
    <SgjTextarea v-model="knownImpact" label="已知业务影响与交接说明" />
    <div class="phase10-actions">
      <SgjButton type="submit" :loading="disabled" :disabled="disabled">提交请假申请</SgjButton>
    </div>
  </form>
</template>
