<script setup lang="ts">
import PageTemplateFrame from './PageTemplateFrame.vue'
defineProps<{ title: string; description?: string }>()
</script>
<template>
  <PageTemplateFrame :title="title" :description="description">
    <template v-if="$slots.actions" #actions><slot name="actions" /></template>
    <div v-if="$slots.summary" class="sgj-page-template__summary"><slot name="summary" /></div>
    <div class="sgj-page-template__surface sgj-page-template__timeline"><slot /></div>
  </PageTemplateFrame>
</template>
