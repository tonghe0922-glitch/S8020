<script setup lang="ts">
import type { SelectOption } from '../types'
import { eventValue } from '../eventValue'
import { useFieldA11y } from '../useFieldA11y'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  options: readonly SelectOption[]
  name?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  modelValue: '',
  required: false,
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()
const field = useFieldA11y({ id: () => props.id, hint: () => props.hint, error: () => props.error, prefix: 'sgj-radio' })

function onChange(event: Event): void {
  const value = eventValue(event)
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <fieldset
    class="sgj-field sgj-radio-group"
    :aria-describedby="field.describedBy.value"
    :aria-invalid="error ? 'true' : undefined"
    :disabled="disabled"
  >
    <legend class="sgj-field__label">{{ label }}<span v-if="required" aria-hidden="true"> *</span></legend>
    <label v-for="option in options" :key="option.value" class="sgj-choice">
      <input
        class="sgj-choice__input"
        type="radio"
        :name="name ?? field.controlId.value"
        :value="option.value"
        :checked="modelValue === option.value"
        :required="required"
        :disabled="disabled || option.disabled"
        @change="onChange"
      >
      <span class="sgj-choice__label">{{ option.label }}</span>
    </label>
    <p v-if="hint" :id="field.hintId.value" class="sgj-field__hint">{{ hint }}</p>
    <p v-if="error" :id="field.errorId.value" class="sgj-field__error" role="alert">{{ error }}</p>
  </fieldset>
</template>
