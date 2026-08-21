<script setup lang="ts">
import PageTemplateFrame from './PageTemplateFrame.vue'
defineProps<{ title: string; description?: string }>()
</script>
<template>
  <PageTemplateFrame :title="title" :description="description">
    <div class="sgj-page-template__split">
      <div class="sgj-page-template__surface"><slot /></div>
      <aside v-if="$slots.aside" class="sgj-page-template__aside"><slot name="aside" /></aside>
    </div>
    <footer v-if="$slots.actions" class="sgj-page-template__sticky-actions"><slot name="actions" /></footer>
  </PageTemplateFrame>
</template>
