<script setup lang="ts">
import Card from './Card.vue'

withDefaults(defineProps<{
  title: string
  subtitle?: string
  ariaLabel?: string
}>(), {
  subtitle: undefined,
  ariaLabel: undefined,
})
</script>

<template>
  <Card as="article" class="sgj-record-card" :aria-label="ariaLabel ?? title">
    <template #header>
      <div class="sgj-record-card__heading">
        <div>
          <h2 class="sgj-record-card__title">{{ title }}</h2>
          <p v-if="subtitle" class="sgj-record-card__subtitle">{{ subtitle }}</p>
        </div>
        <div v-if="$slots.status" class="sgj-record-card__status"><slot name="status" /></div>
      </div>
    </template>
    <slot />
    <template v-if="$slots.footer || $slots.actions" #footer>
      <div class="sgj-record-card__footer">
        <div v-if="$slots.footer"><slot name="footer" /></div>
        <div v-if="$slots.actions" class="sgj-record-card__actions"><slot name="actions" /></div>
      </div>
    </template>
  </Card>
</template>
