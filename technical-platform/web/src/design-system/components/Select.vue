<script setup lang="ts">
import { eventValue } from '../eventValue'
import type { SelectOption } from '../types'
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  options: readonly SelectOption[]
  name?: string
  placeholder?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  modelValue: '',
  placeholder: '请选择',
  required: false,
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; blur: [event: FocusEvent] }>()
const field = useFieldA11y({
  id: () => props.id,
  hint: () => props.hint,
  error: () => props.error,
  prefix: 'sgj-select',
})
</script>

<template>
  <FieldFrame
    :label="label"
    :control-id="field.controlId.value"
    :required="required"
    :hint="hint"
    :error="error"
    :hint-id="field.hintId.value"
    :error-id="field.errorId.value"
  >
    <select
      :id="field.controlId.value"
      class="sgj-control sgj-control--select"
      :name="name"
      :value="modelValue"
      :required="required"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="field.describedBy.value"
      @change="emit('update:modelValue', eventValue($event))"
      @blur="emit('blur', $event)"
    >
      <option value="" :disabled="required">{{ placeholder }}</option>
      <option v-for="option in options" :key="option.value" :value="option.value" :disabled="option.disabled">
        {{ option.label }}
      </option>
    </select>
  </FieldFrame>
</template>
