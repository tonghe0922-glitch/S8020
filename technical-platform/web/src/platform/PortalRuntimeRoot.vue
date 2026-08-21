<script setup lang="ts">
import { onErrorCaptured } from 'vue'
import { RouterView } from 'vue-router'
import { SgjButton, SgjError } from '../design-system'
import { clearRuntimeError, recordRuntimeError, useRuntimeErrorState } from './runtime-error-state'

const failure = useRuntimeErrorState()

onErrorCaptured((cause) => {
  recordRuntimeError(cause)
  return false
})
</script>

<template>
  <div v-if="failure" class="platform-runtime-error">
    <SgjError
      title="页面运行异常"
      :description="failure.message"
      :trace-id="failure.requestId"
    >
      <template #actions>
        <SgjButton variant="secondary" @click="clearRuntimeError">返回当前页面</SgjButton>
      </template>
    </SgjError>
  </div>
  <RouterView v-else />
</template>
