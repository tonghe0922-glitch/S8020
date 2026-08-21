<script setup lang="ts">
import { computed } from 'vue'
import type { AvatarSize } from '../types'

const props = withDefaults(defineProps<{
  name: string
  src?: string
  alt?: string
  size?: AvatarSize
}>(), {
  size: 'md',
})

const initials = computed(() => {
  const compact = props.name.trim().replace(/\s+/g, '')
  return compact.slice(0, 2) || '人'
})
</script>

<template>
  <span class="sgj-avatar" :class="`sgj-avatar--${size}`" role="img" :aria-label="alt ?? name">
    <img v-if="src" class="sgj-avatar__image" :src="src" :alt="alt ?? name">
    <span v-else class="sgj-avatar__fallback" aria-hidden="true">{{ initials }}</span>
  </span>
</template>
