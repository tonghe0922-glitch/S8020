<script setup lang="ts">
import type { KpiTone } from '../types'
import Card from './Card.vue'

withDefaults(defineProps<{
  label: string
  value: string | number
  unit: string
  definition: string
  tone?: KpiTone
}>(), {
  tone: 'neutral',
})
</script>

<template>
  <Card as="article" class="sgj-kpi-card" :class="`sgj-kpi-card--${tone}`" :aria-label="`${label}：${value}${unit}`">
    <p class="sgj-kpi-card__label">{{ label }}</p>
    <p class="sgj-kpi-card__value"><strong>{{ value }}</strong><span>{{ unit }}</span></p>
    <p class="sgj-kpi-card__definition">{{ definition }}</p>
  </Card>
</template>
