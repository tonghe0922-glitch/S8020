<script setup lang="ts">
import StatePanel from './StatePanel.vue'
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '暂无访问权限', description: '当前身份无法访问此内容。权限结果以服务端裁决为准。',
})
</script>
<template><StatePanel tone="warning" role="alert" :title="title" :description="description"><template #icon>!</template><template v-if="$slots.actions" #actions><slot name="actions" /></template></StatePanel></template>
