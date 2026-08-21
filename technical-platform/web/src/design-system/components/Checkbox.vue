<script setup lang="ts">
import { eventChecked } from '../eventChecked'
import { useFieldA11y } from '../useFieldA11y'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: boolean
  name?: string
  value?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  modelValue: false,
  value: 'true',
  required: false,
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; change: [value: boolean] }>()
const field = useFieldA11y({ id: () => props.id, hint: () => props.hint, error: () => props.error, prefix: 'sgj-checkbox' })

function onChange(event: Event): void {
  const checked = eventChecked(event)
  emit('update:modelValue', checked)
  emit('change', checked)
}
</script>

<template>
  <div class="sgj-field" :class="{ 'sgj-field--error': Boolean(error) }">
    <label class="sgj-choice" :for="field.controlId.value">
      <input
        :id="field.controlId.value"
        class="sgj-choice__input"
        type="checkbox"
        :name="name"
        :value="value"
        :checked="modelValue"
        :required="required"
        :disabled="disabled"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="field.describedBy.value"
        @change="onChange"
      >
      <span class="sgj-choice__label">{{ label }}<span v-if="required" aria-hidden="true"> *</span></span>
    </label>
    <p v-if="hint" :id="field.hintId.value" class="sgj-field__hint">{{ hint }}</p>
    <p v-if="error" :id="field.errorId.value" class="sgj-field__error" role="alert">{{ error }}</p>
  </div>
</template>
