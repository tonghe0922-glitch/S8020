<script setup lang="ts">
import StatePanel from './StatePanel.vue'
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '数据已发生变化', description: '请刷新最新事实后再继续，避免覆盖其他人的更新。',
})
</script>
<template><StatePanel tone="warning" role="alert" :title="title" :description="description"><template #icon>↻</template><template v-if="$slots.actions" #actions><slot name="actions" /></template></StatePanel></template>
