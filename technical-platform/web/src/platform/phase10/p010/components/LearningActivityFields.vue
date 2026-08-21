<script setup lang="ts">
import { SgjInput, SgjTextarea } from '../../../../design-system'

const completionRate = defineModel<string>('completionRate', { required: true })
const score1000 = defineModel<string>('score1000', { required: true })
const practicalResult = defineModel<string>('practicalResult', { required: true })
const note = defineModel<string>('note', { required: true })
const effectiveDate = defineModel<string>('effectiveDate', { required: true })
const expireDate = defineModel<string>('expireDate', { required: true })

withDefaults(defineProps<{
  showProgress?: boolean
  showExam?: boolean
  showPractical?: boolean
  showDates?: boolean
}>(), {
  showProgress: false,
  showExam: false,
  showPractical: false,
  showDates: false,
})
</script>

<template>
  <div class="phase10-form">
    <SgjInput
      v-if="showProgress"
      v-model="completionRate"
      label="学习完成率（%）"
      type="number"
      min="0"
      max="100"
      step="0.01"
    />
    <SgjInput
      v-if="showExam"
      v-model="score1000"
      label="考试成绩（1000 分制）"
      type="number"
      min="0"
      max="1000"
      step="1"
    />
    <SgjInput v-if="showPractical" v-model="practicalResult" label="实操结果" />
    <template v-if="showDates">
      <SgjInput v-model="effectiveDate" label="资格生效日期" type="date" />
      <SgjInput v-model="expireDate" label="资格失效日期" type="date" />
    </template>
    <SgjTextarea v-model="note" label="学习/认证说明" />
  </div>
</template>
