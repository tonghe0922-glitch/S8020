<script setup lang="ts">
import StatePanel from './StatePanel.vue'
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '正在加载', description: '正在获取最新信息，请稍候。',
})
</script>
<template><StatePanel tone="info" :title="title" :description="description" :busy="true"><template #icon><span class="sgj-state-spinner" /></template></StatePanel></template>
