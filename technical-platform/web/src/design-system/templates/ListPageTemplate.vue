<script setup lang="ts">
import PageTemplateFrame from './PageTemplateFrame.vue'
defineProps<{ title: string; description?: string }>()
</script>
<template>
  <PageTemplateFrame :title="title" :description="description">
    <template v-if="$slots.actions" #actions><slot name="actions" /></template>
    <div v-if="$slots.filters" class="sgj-page-template__filters"><slot name="filters" /></div>
    <div class="sgj-page-template__surface"><slot /></div>
    <footer v-if="$slots.pagination" class="sgj-page-template__footer"><slot name="pagination" /></footer>
  </PageTemplateFrame>
</template>
