<script setup lang="ts">
import type { ButtonSize, ButtonVariant } from '../types'

withDefaults(defineProps<{
  variant?: ButtonVariant
  size?: ButtonSize
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
  block?: boolean
}>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  disabled: false,
  loading: false,
  block: false,
})

defineEmits<{ click: [event: MouseEvent] }>()
</script>

<template>
  <button
    class="sgj-button"
    :class="[`sgj-button--${variant}`, `sgj-button--${size}`, { 'sgj-button--block': block }]"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
    @click="$emit('click', $event)"
  >
    <span v-if="loading" class="sgj-button__spinner" aria-hidden="true" />
    <span><slot /></span>
    <span v-if="loading" class="sgj-sr-only">处理中</span>
  </button>
</template>
