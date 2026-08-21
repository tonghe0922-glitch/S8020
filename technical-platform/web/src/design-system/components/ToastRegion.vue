<script setup lang="ts">
import { computed } from 'vue'
import type { ToastItem } from '../types'
import Toast from './Toast.vue'

const props = withDefaults(defineProps<{
  items: readonly ToastItem[]
  maxVisible?: number
  ariaLabel?: string
}>(), {
  maxVisible: 5,
  ariaLabel: '全局提示',
})

const emit = defineEmits<{ dismiss: [id: string] }>()
const visibleItems = computed(() => {
  const seen = new Set<string>()
  return props.items
    .filter((item) => {
      if (seen.has(item.id)) return false
      seen.add(item.id)
      return true
    })
    .slice(0, Math.max(1, props.maxVisible))
})
</script>

<template>
  <div class="sgj-toast-region" :aria-label="ariaLabel">
    <Toast
      v-for="item in visibleItems"
      :key="item.id"
      :tone="item.tone ?? 'neutral'"
      :title="item.title"
      :message="item.message"
      :dismissible="item.dismissible ?? true"
      @dismiss="emit('dismiss', item.id)"
    />
  </div>
</template>
