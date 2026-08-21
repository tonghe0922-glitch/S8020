<script setup lang="ts">
withDefaults(defineProps<{
  value?: string
  revealed?: boolean
  mask?: string
  maskedLabel?: string
}>(), {
  value: '',
  revealed: false,
  mask: '••••••',
  maskedLabel: '敏感信息已隐藏',
})
</script>

<template>
  <span class="sgj-masked-value">
    <span v-if="revealed"><slot :value="value">{{ value }}</slot></span>
    <span v-else :aria-label="maskedLabel">{{ mask }}</span>
  </span>
</template>
