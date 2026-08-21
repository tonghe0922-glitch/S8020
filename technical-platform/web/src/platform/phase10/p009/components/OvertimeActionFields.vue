<script setup lang="ts">
import {
  SgjDateTime,
  SgjInput,
  SgjSelect,
  SgjTextarea,
  type SelectOption,
} from '../../../../design-system'

const reason = defineModel<string>('reason', { required: true })
const actualStartAt = defineModel<string>('actualStartAt', { required: true })
const actualEndAt = defineModel<string>('actualEndAt', { required: true })
const attendanceSummary = defineModel<string>('attendanceSummary', { required: true })
const resultSummary = defineModel<string>('resultSummary', { required: true })
const compensationPlan = defineModel<string>('compensationPlan', { required: true })
const wageAmount = defineModel<string>('wageAmount', { required: true })
const quotaAccountId = defineModel<string>('quotaAccountId', { required: true })
const timeOffHours = defineModel<string>('timeOffHours', { required: true })
const payrollReference = defineModel<string>('payrollReference', { required: true })

withDefaults(defineProps<{
  showActual?: boolean
  showResult?: boolean
  showCompensation?: boolean
  showPayroll?: boolean
}>(), {
  showActual: false,
  showResult: false,
  showCompensation: false,
  showPayroll: false,
})

const planOptions: readonly SelectOption[] = [
  { value: 'WAGE', label: '依法计发工资' },
  { value: 'TIME_OFF', label: '调休额度' },
  { value: 'MIXED', label: '工资与调休组合' },
]
</script>

<template>
  <div class="phase10-form">
    <SgjTextarea v-model="reason" label="处理说明" />
    <template v-if="showActual">
      <SgjDateTime v-model="actualStartAt" label="实际开始" mode="datetime" />
      <SgjDateTime v-model="actualEndAt" label="实际结束" mode="datetime" />
      <SgjTextarea v-model="attendanceSummary" label="实际考勤与劳动事实" />
    </template>
    <SgjTextarea v-if="showResult" v-model="resultSummary" label="成果验收说明" />
    <template v-if="showCompensation">
      <SgjSelect v-model="compensationPlan" label="补偿方案" :options="planOptions" />
      <SgjInput v-model="wageAmount" label="工资金额" type="number" min="0" step="0.01" />
      <SgjInput v-model="quotaAccountId" label="调休额度账户" />
      <SgjInput v-model="timeOffHours" label="调休小时数" type="number" min="0" step="0.5" />
    </template>
    <SgjInput v-if="showPayroll" v-model="payrollReference" label="薪酬回执编号" />
  </div>
</template>
