<script setup lang="ts">
defineProps<{
  label: string
  controlId: string
  required?: boolean
  hint?: string
  error?: string
  hintId: string
  errorId: string
}>()
</script>

<template>
  <div class="sgj-field" :class="{ 'sgj-field--error': Boolean(error) }">
    <label class="sgj-field__label" :for="controlId">
      {{ label }}
      <span v-if="required" class="sgj-field__required" aria-hidden="true">*</span>
      <span v-if="required" class="sgj-sr-only">必填</span>
    </label>
    <slot />
    <p v-if="hint" :id="hintId" class="sgj-field__hint">{{ hint }}</p>
    <p v-if="error" :id="errorId" class="sgj-field__error" role="alert">{{ error }}</p>
  </div>
</template>
