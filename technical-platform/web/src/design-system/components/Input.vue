<script setup lang="ts">
import { eventValue } from '../eventValue'
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  type?: 'text' | 'email' | 'tel' | 'url' | 'password' | 'search' | 'number' | 'date' | 'time' | 'datetime-local'
  name?: string
  placeholder?: string
  autocomplete?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
  readonly?: boolean
  min?: string
  max?: string
  step?: string | number
}>(), {
  modelValue: '',
  type: 'text',
  required: false,
  disabled: false,
  readonly: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; blur: [event: FocusEvent] }>()
const field = useFieldA11y({
  id: () => props.id,
  hint: () => props.hint,
  error: () => props.error,
  prefix: 'sgj-input',
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
    <input
      :id="field.controlId.value"
      class="sgj-control"
      :type="type"
      :name="name"
      :value="modelValue"
      :placeholder="placeholder"
      :autocomplete="autocomplete"
      :required="required"
      :disabled="disabled"
      :readonly="readonly"
      :min="min"
      :max="max"
      :step="step"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="field.describedBy.value"
      @input="emit('update:modelValue', eventValue($event))"
      @blur="emit('blur', $event)"
    >
  </FieldFrame>
</template>
