<script setup lang="ts">
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: boolean
  hint?: string
  error?: string
  disabled?: boolean
  onLabel?: string
  offLabel?: string
}>(), {
  modelValue: false,
  disabled: false,
  onLabel: '已开启',
  offLabel: '已关闭',
})

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; change: [value: boolean] }>()
const field = useFieldA11y({ id: () => props.id, hint: () => props.hint, error: () => props.error, prefix: 'sgj-switch' })

function toggle(): void {
  if (props.disabled) return
  const next = !props.modelValue
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<template>
  <FieldFrame
    :label="label"
    :control-id="field.controlId.value"
    :hint="hint"
    :error="error"
    :hint-id="field.hintId.value"
    :error-id="field.errorId.value"
  >
    <button
      :id="field.controlId.value"
      class="sgj-switch"
      type="button"
      role="switch"
      :aria-checked="modelValue"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="field.describedBy.value"
      :disabled="disabled"
      @click="toggle"
    >
      <span class="sgj-switch__track" aria-hidden="true"><span class="sgj-switch__thumb" /></span>
      <span class="sgj-switch__state">{{ modelValue ? onLabel : offLabel }}</span>
    </button>
  </FieldFrame>
</template>
