<script setup lang="ts">
import { computed } from 'vue'
import type { ToastTone } from '../types'

const props = withDefaults(defineProps<{
  tone?: ToastTone
  title: string
  message?: string
  dismissible?: boolean
}>(), {
  tone: 'info',
  message: undefined,
  dismissible: true,
})

defineEmits<{ dismiss: [] }>()
const liveRole = computed(() => props.tone === 'danger' ? 'alert' : 'status')
const liveMode = computed(() => props.tone === 'danger' ? 'assertive' : 'polite')
</script>

<template>
  <section class="sgj-toast" :class="`sgj-toast--${tone}`" :role="liveRole" :aria-live="liveMode" aria-atomic="true">
    <span class="sgj-toast__marker" aria-hidden="true">●</span>
    <div class="sgj-toast__content">
      <strong>{{ title }}</strong>
      <p v-if="message">{{ message }}</p>
    </div>
    <button v-if="dismissible" class="sgj-toast__close" type="button" aria-label="关闭提示" @click="$emit('dismiss')">×</button>
  </section>
</template>
