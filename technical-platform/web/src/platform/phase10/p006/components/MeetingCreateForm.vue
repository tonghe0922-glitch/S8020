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
const content = defineModel<string>('content', { required: true })
const venue = defineModel<string>('venue', { required: true })
const startAt = defineModel<string>('startAt', { required: true })
const visibility = defineModel<string>('visibility', { required: true })
const attendanceType = defineModel<string>('attendanceType', { required: true })
const participants = defineModel<string>('participants', { required: true })
const agenda = defineModel<string>('agenda', { required: true })

defineProps<{ disabled: boolean }>()
defineEmits<{ submit: [] }>()

const visibilityOptions: readonly SelectOption[] = [
  { value: '内部', label: '内部' },
  { value: '公开', label: '公开' },
  { value: '秘密', label: '秘密' },
  { value: '机密', label: '机密' },
]
const attendanceOptions: readonly SelectOption[] = [
  { value: '现场', label: '现场' },
  { value: '线上', label: '线上' },
  { value: '混合', label: '混合' },
]
</script>

<template>
  <form class="phase10-form" @submit.prevent="$emit('submit')">
    <div class="phase10-form-grid">
      <SgjInput v-model="subject" label="会议主题" required />
      <SgjDateTime v-model="startAt" label="召开时间" mode="datetime" required />
      <SgjInput v-model="venue" label="地点/渠道" />
      <SgjInput v-model="participants" label="参会员工 UUID（逗号分隔）" />
      <SgjSelect v-model="attendanceType" label="出席类型" :options="attendanceOptions" />
      <SgjSelect v-model="visibility" label="可见范围" :options="visibilityOptions" />
    </div>
    <SgjTextarea v-model="content" label="议题正文" />
    <SgjTextarea v-model="agenda" label="议题清单（每行一项）" />
    <div class="phase10-actions">
      <SgjButton type="submit" :loading="disabled" :disabled="disabled">创建会议</SgjButton>
    </div>
  </form>
</template>
