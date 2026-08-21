<script setup lang="ts">
import StatePanel from './StatePanel.vue'

withDefaults(defineProps<{
  title?: string
  description?: string
  errorCode?: string
  traceId?: string
}>(), {
  title: '加载失败',
  description: '暂时无法完成当前操作，请检查后重试。',
  errorCode: undefined,
  traceId: undefined,
})
</script>

<template>
  <StatePanel tone="danger" role="alert" :title="title" :description="description">
    <template #icon>!</template>
    <template v-if="errorCode || traceId" #details>
      <dl class="sgj-error-meta" aria-label="错误诊断信息">
        <div v-if="errorCode">
          <dt>错误编号</dt>
          <dd><code>{{ errorCode }}</code></dd>
        </div>
        <div v-if="traceId">
          <dt>Trace ID</dt>
          <dd><code>{{ traceId }}</code></dd>
        </div>
      </dl>
    </template>
    <template v-if="$slots.actions" #actions><slot name="actions" /></template>
  </StatePanel>
</template>
