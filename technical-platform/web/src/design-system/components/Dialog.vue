<script setup lang="ts">
import { computed, useId } from 'vue'
import { useModalFocus } from '../useModalFocus'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  closeLabel?: string
  closeOnBackdrop?: boolean
}>(), {
  description: undefined,
  closeLabel: '关闭',
  closeOnBackdrop: true,
})

const emit = defineEmits<{ close: [] }>()
const uid = useId()
const titleId = `sgj-dialog-${uid}-title`
const descriptionId = `sgj-dialog-${uid}-description`
const describedBy = computed(() => props.description ? descriptionId : undefined)
const { panelRef, trapFocus } = useModalFocus(() => props.open)

function closeFromBackdrop(): void {
  if (props.closeOnBackdrop) emit('close')
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  trapFocus(event)
}
</script>

<template>
  <div v-if="open" class="sgj-overlay" @click.self="closeFromBackdrop">
    <section
      ref="panelRef"
      class="sgj-dialog"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      :aria-describedby="describedBy"
      tabindex="-1"
      @keydown="handleKeydown"
    >
      <header class="sgj-overlay__header">
        <div>
          <h2 :id="titleId" class="sgj-overlay__title">{{ title }}</h2>
          <p v-if="description" :id="descriptionId" class="sgj-overlay__description">{{ description }}</p>
        </div>
        <button class="sgj-overlay__close" type="button" :aria-label="closeLabel" @click="emit('close')">×</button>
      </header>
      <div class="sgj-overlay__body"><slot /></div>
      <footer v-if="$slots.footer" class="sgj-overlay__footer"><slot name="footer" /></footer>
    </section>
  </div>
</template>
