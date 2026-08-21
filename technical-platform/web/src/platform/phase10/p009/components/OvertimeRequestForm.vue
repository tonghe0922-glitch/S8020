<script setup lang="ts">
import {
  SgjButton,
  SgjDateTime,
  SgjInput,
  SgjSelect,
  SgjSwitch,
  SgjTextarea,
  type SelectOption,
} from '../../../../design-system'

const subject = defineModel<string>('subject', { required: true })
const reason = defineModel<string>('reason', { required: true })
const attendanceType = defineModel<string>('attendanceType', { required: true })
const startAt = defineModel<string>('startAt', { required: true })
const endAt = defineModel<string>('endAt', { required: true })
const emergencyFact = defineModel<boolean>('emergencyFact', { required: true })

defineProps<{ disabled: boolean }>()
defineEmits<{ submit: [] }>()

const attendanceTypes: readonly SelectOption[] = [
  { value: 'OVERTIME', label: '计划加班' },
  { value: 'EMERGENCY_OVERTIME', label: '紧急加班事实' },
  { value: 'EVENT_SUPPORT', label: '活动保障' },
]
</script>

<template>
  <form class="phase10-form" @submit.prevent="$emit('submit')">
    <div class="phase10-form-grid">
      <SgjInput v-model="subject" label="任务主题" required placeholder="例如：夜场活动保障" />
      <SgjSelect v-model="attendanceType" label="劳动事实类型" required :options="attendanceTypes" />
      <SgjDateTime v-model="startAt" label="计划开始" mode="datetime" required />
      <SgjDateTime v-model="endAt" label="计划结束" mode="datetime" required />
      <SgjSwitch
        v-model="emergencyFact"
        label="是否紧急事实登记"
        hint="紧急登记仍需补齐审批与实际劳动事实。"
      />
    </div>
    <SgjTextarea v-model="reason" label="必要性说明" required />
    <div class="phase10-actions">
      <SgjButton type="submit" :loading="disabled" :disabled="disabled">提交加班申请</SgjButton>
    </div>
  </form>
</template>
