<script setup lang="ts">
import PageTemplateFrame from './PageTemplateFrame.vue'
defineProps<{ title: string; description?: string }>()
</script>
<template>
  <PageTemplateFrame :title="title" :description="description">
    <div class="sgj-page-template__split">
      <div class="sgj-page-template__stack">
        <div v-if="$slots.context" class="sgj-page-template__surface"><slot name="context" /></div>
        <div class="sgj-page-template__surface"><slot /></div>
      </div>
      <aside v-if="$slots.decision" class="sgj-page-template__aside"><slot name="decision" /></aside>
    </div>
    <div v-if="$slots.timeline" class="sgj-page-template__surface"><slot name="timeline" /></div>
  </PageTemplateFrame>
</template>
