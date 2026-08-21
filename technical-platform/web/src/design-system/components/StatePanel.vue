<script setup lang="ts">
import type { StatusTone } from '../types'

withDefaults(defineProps<{
  tone?: StatusTone
  title: string
  description?: string
  role?: 'status' | 'alert'
  busy?: boolean
}>(), {
  tone: 'neutral',
  description: undefined,
  role: 'status',
  busy: false,
})
</script>

<template>
  <section class="sgj-state-panel" :class="`sgj-state-panel--${tone}`" :role="role" :aria-busy="busy || undefined">
    <div class="sgj-state-panel__icon" aria-hidden="true"><slot name="icon">●</slot></div>
    <div class="sgj-state-panel__content">
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.details" class="sgj-state-panel__details"><slot name="details" /></div>
      <div v-if="$slots.actions" class="sgj-state-panel__actions"><slot name="actions" /></div>
    </div>
  </section>
</template>
