<script setup lang="ts">
withDefaults(defineProps<{
  revealed?: boolean
  busy?: boolean
  revealLabel?: string
  concealLabel?: string
  maskedLabel?: string
}>(), {
  revealed: false,
  busy: false,
  revealLabel: '验证身份后查看',
  concealLabel: '隐藏敏感信息',
  maskedLabel: '敏感信息已隐藏',
})

defineEmits<{ requestReveal: []; conceal: [] }>()
</script>

<template>
  <div class="sgj-step-up-reveal">
    <div class="sgj-step-up-reveal__content">
      <slot v-if="revealed" name="revealed" />
      <slot v-else name="masked"><span :aria-label="maskedLabel">••••••</span></slot>
    </div>
    <button
      v-if="!revealed"
      class="sgj-step-up-reveal__action"
      type="button"
      :disabled="busy"
      :aria-busy="busy || undefined"
      @click="$emit('requestReveal')"
    >
      {{ busy ? '验证中…' : revealLabel }}
    </button>
    <button v-else class="sgj-step-up-reveal__action" type="button" @click="$emit('conceal')">
      {{ concealLabel }}
    </button>
  </div>
</template>
