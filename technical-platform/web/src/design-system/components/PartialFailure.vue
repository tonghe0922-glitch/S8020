<script setup lang="ts">
import StatePanel from './StatePanel.vue'

withDefaults(defineProps<{
  title?: string
  description?: string
}>(), {
  title: '部分操作未完成',
  description: '部分项目已成功，仍有项目需要继续处理。请查看明细后重试或转人工处理。',
})
</script>

<template>
  <StatePanel tone="warning" role="alert" :title="title" :description="description">
    <template #icon>!</template>
    <template v-if="$slots.details" #details><slot name="details" /></template>
    <template v-if="$slots.actions" #actions><slot name="actions" /></template>
  </StatePanel>
</template>
