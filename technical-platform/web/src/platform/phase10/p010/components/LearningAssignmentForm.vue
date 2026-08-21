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
const ownerEmployeeId = defineModel<string>('ownerEmployeeId', { required: true })
const contentVersion = defineModel<string>('contentVersion', { required: true })
const courseTeamName = defineModel<string>('courseTeamName', { required: true })
const courseVersionId = defineModel<string>('courseVersionId', { required: true })
const learnerProfile = defineModel<string>('learnerProfile', { required: true })
const periodOrCourseNo = defineModel<string>('periodOrCourseNo', { required: true })
const riskLevel = defineModel<string>('riskLevel', { required: true })
const plannedStartAt = defineModel<string>('plannedStartAt', { required: true })
const plannedFinishAt = defineModel<string>('plannedFinishAt', { required: true })

defineProps<{ disabled: boolean }>()
defineEmits<{ submit: [] }>()

const riskOptions: readonly SelectOption[] = [
  { value: 'NORMAL', label: '一般岗位' },
  { value: 'IMPORTANT', label: '重要岗位' },
  { value: 'HIGH_RISK', label: '高风险岗位' },
]
</script>

<template>
  <form class="phase10-form" @submit.prevent="$emit('submit')">
    <div class="phase10-form-grid">
      <SgjInput v-model="subject" label="学习任务" required />
      <SgjInput v-model="ownerEmployeeId" label="目标员工 UUID" required />
      <SgjInput v-model="contentVersion" label="内容版本" required />
      <SgjInput v-model="courseVersionId" label="课程版本 ID" required />
      <SgjInput v-model="courseTeamName" label="课程负责团队" required />
      <SgjInput v-model="periodOrCourseNo" label="期次/课程编号" required />
      <SgjSelect v-model="riskLevel" label="岗位风险等级" :options="riskOptions" required />
      <SgjDateTime v-model="plannedStartAt" label="计划开始" mode="datetime" />
      <SgjDateTime v-model="plannedFinishAt" label="计划完成" mode="datetime" />
    </div>
    <SgjTextarea v-model="learnerProfile" label="学习者画像与适配说明" />
    <SgjTextarea v-model="reason" label="指派原因" />
    <div class="phase10-actions">
      <SgjButton type="submit" :loading="disabled" :disabled="disabled">创建学习任务</SgjButton>
    </div>
  </form>
</template>
