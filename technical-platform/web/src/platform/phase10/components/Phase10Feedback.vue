<script setup lang="ts">
defineProps<{
  message: string
  failed: boolean
}>()
</script>

<template>
  <p
    v-if="message"
    class="phase10-feedback"
    :class="{ 'phase10-feedback--error': failed }"
    :role="failed ? 'alert' : 'status'"
  >
    {{ message }}
  </p>
</template>
