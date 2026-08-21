<script setup lang="ts">
import type { StatusTone } from '../types'

withDefaults(defineProps<{
  tone?: StatusTone
  ariaLabel?: string
}>(), {
  tone: 'neutral',
  ariaLabel: undefined,
})
</script>

<template>
  <span class="sgj-status-chip" :class="`sgj-status-chip--${tone}`" :aria-label="ariaLabel">
    <span class="sgj-status-chip__marker" aria-hidden="true">●</span>
    <span class="sgj-status-chip__text"><slot /></span>
  </span>
</template>
