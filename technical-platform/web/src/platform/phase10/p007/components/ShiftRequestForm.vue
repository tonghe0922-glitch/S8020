<script setup lang="ts">
import { SgjButton, SgjDateTime, SgjInput, SgjTextarea } from '../../../../design-system'

const subject = defineModel<string>('subject', { required: true })
const changeReason = defineModel<string>('changeReason', { required: true })
const templateCode = defineModel<string>('templateCode', { required: true })
const period = defineModel<string>('period', { required: true })
const targetEmployee = defineModel<string>('targetEmployee', { required: true })
const startAt = defineModel<string>('startAt', { required: true })
const endAt = defineModel<string>('endAt', { required: true })

defineProps<{ center: boolean; disabled: boolean }>()
defineEmits<{ submit: [] }>()
</script>

<template>
  <form class="phase10-form" @submit.prevent="$emit('submit')">
    <div class="phase10-form-grid">
      <SgjInput v-model="subject" label="主题" required />
      <SgjInput v-if="center" v-model="targetEmployee" label="目标员工 UUID" required />
      <SgjInput v-model="templateCode" label="班次模板" />
      <SgjInput v-model="period" label="周期编号" required />
      <SgjDateTime v-model="startAt" label="开始时间" mode="datetime" required />
      <SgjDateTime v-model="endAt" label="结束时间" mode="datetime" required />
    </div>
    <SgjTextarea v-model="changeReason" label="变更原因" />
    <div class="phase10-actions">
      <SgjButton type="submit" :loading="disabled" :disabled="disabled">
        提交排班/换班需求
      </SgjButton>
    </div>
  </form>
</template>
