<script setup lang="ts">
import StatePanel from './StatePanel.vue'
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '暂无内容', description: '当前没有可显示的记录。',
})
</script>
<template><StatePanel :title="title" :description="description"><template #icon>○</template><template v-if="$slots.actions" #actions><slot name="actions" /></template></StatePanel></template>
