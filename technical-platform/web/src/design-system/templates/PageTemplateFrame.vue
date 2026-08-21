<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>(), {
  description: undefined,
  eyebrow: undefined,
})
</script>

<template>
  <section class="sgj-page-template">
    <header class="sgj-page-template__header">
      <div>
        <p v-if="eyebrow" class="sgj-page-template__eyebrow">{{ eyebrow }}</p>
        <h1 class="sgj-page-template__title">{{ title }}</h1>
        <p v-if="description" class="sgj-page-template__description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="sgj-page-template__actions"><slot name="actions" /></div>
    </header>
    <slot />
  </section>
</template>
