<script setup lang="ts">
import { computed, useId } from 'vue'
import type { DrawerSide } from '../types'
import { useModalFocus } from '../useModalFocus'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  description?: string
  side?: DrawerSide
  closeLabel?: string
}>(), {
  description: undefined,
  side: 'right',
  closeLabel: '关闭抽屉',
})

const emit = defineEmits<{ close: [] }>()
const uid = useId()
const titleId = `sgj-drawer-${uid}-title`
const descriptionId = `sgj-drawer-${uid}-description`
const describedBy = computed(() => props.description ? descriptionId : undefined)
const { panelRef, trapFocus } = useModalFocus(() => props.open)

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  trapFocus(event)
}
</script>

<template>
  <div v-if="open" class="sgj-overlay sgj-overlay--drawer" @click.self="emit('close')">
    <aside
      ref="panelRef"
      class="sgj-drawer"
      :class="`sgj-drawer--${side}`"
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
    </aside>
  </div>
</template>
