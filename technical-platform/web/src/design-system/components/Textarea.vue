<script setup lang="ts">
import { eventValue } from '../eventValue'
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  name?: string
  placeholder?: string
  hint?: string
  error?: string
  rows?: number
  required?: boolean
  disabled?: boolean
  readonly?: boolean
}>(), {
  modelValue: '',
  rows: 4,
  required: false,
  disabled: false,
  readonly: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; blur: [event: FocusEvent] }>()
const field = useFieldA11y({
  id: () => props.id,
  hint: () => props.hint,
  error: () => props.error,
  prefix: 'sgj-textarea',
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
    <textarea
      :id="field.controlId.value"
      class="sgj-control sgj-control--textarea"
      :name="name"
      :rows="rows"
      :value="modelValue"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :readonly="readonly"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="field.describedBy.value"
      @input="emit('update:modelValue', eventValue($event))"
      @blur="emit('blur', $event)"
    />
  </FieldFrame>
</template>
