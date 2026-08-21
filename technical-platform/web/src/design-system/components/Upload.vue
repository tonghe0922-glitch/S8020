<script setup lang="ts">
import { eventFiles } from '../eventFiles'
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  accept?: string
  multiple?: boolean
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  multiple: false,
  required: false,
  disabled: false,
})

const emit = defineEmits<{ change: [files: File[]] }>()
const field = useFieldA11y({ id: () => props.id, hint: () => props.hint, error: () => props.error, prefix: 'sgj-upload' })
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
      class="sgj-control sgj-control--file"
      type="file"
      :accept="accept"
      :multiple="multiple"
      :required="required"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="field.describedBy.value"
      @change="emit('change', eventFiles($event))"
    >
  </FieldFrame>
</template>
