<script setup lang="ts">
import PageTemplateFrame from './PageTemplateFrame.vue'
defineProps<{ title: string; description?: string }>()
</script>
<template>
  <PageTemplateFrame :title="title" :description="description">
    <template v-if="$slots.actions" #actions><slot name="actions" /></template>
    <div v-if="$slots.kpis" class="sgj-page-template__kpis"><slot name="kpis" /></div>
    <div class="sgj-page-template__split">
      <div class="sgj-page-template__surface"><slot /></div>
      <aside v-if="$slots.aside" class="sgj-page-template__aside"><slot name="aside" /></aside>
    </div>
  </PageTemplateFrame>
</template>
