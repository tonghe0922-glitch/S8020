<script setup lang="ts">
import { SgjDateTime, SgjInput, SgjTextarea } from '../../../../design-system'

const reason = defineModel<string>('reason', { required: true })
const actualAt = defineModel<string>('actualAt', { required: true })
const adjustmentAmount = defineModel<string>('adjustmentAmount', { required: true })

withDefaults(defineProps<{
  showActualAt?: boolean
  showAdjustment?: boolean
}>(), {
  showActualAt: false,
  showAdjustment: false,
})
</script>

<template>
  <div class="phase10-form">
    <SgjTextarea v-model="reason" label="处理说明" hint="说明会随业务动作写入审计轨迹。" />
    <SgjDateTime v-if="showActualAt" v-model="actualAt" label="实际时间" mode="datetime" />
    <SgjInput
      v-if="showAdjustment"
      v-model="adjustmentAmount"
      label="差额调整"
      type="number"
      step="0.5"
    />
  </div>
</template>
