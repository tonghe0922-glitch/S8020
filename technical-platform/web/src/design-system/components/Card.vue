<script setup lang="ts">
import type { CardVariant } from '../types'

withDefaults(defineProps<{
  variant?: CardVariant
  as?: 'div' | 'section' | 'article'
}>(), {
  variant: 'default',
  as: 'section',
})
</script>

<template>
  <component :is="as" class="sgj-card" :class="`sgj-card--${variant}`">
    <header v-if="$slots.header" class="sgj-card__header"><slot name="header" /></header>
    <div class="sgj-card__body"><slot /></div>
    <footer v-if="$slots.footer" class="sgj-card__footer"><slot name="footer" /></footer>
  </component>
</template>
