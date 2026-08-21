<script setup lang="ts">
import { computed } from 'vue'
import type { DateTimeMode } from '../types'
import Input from './Input.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  mode?: DateTimeMode
  name?: string
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
  mode: 'date',
  required: false,
  disabled: false,
  readonly: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; blur: [event: FocusEvent] }>()
const inputType = computed(() => props.mode === 'datetime' ? 'datetime-local' : props.mode)
</script>

<template>
  <Input
    :id="id"
    :label="label"
    :model-value="modelValue"
    :type="inputType"
    :name="name"
    :hint="hint"
    :error="error"
    :required="required"
    :disabled="disabled"
    :readonly="readonly"
    :min="min"
    :max="max"
    :step="step"
    @update:model-value="emit('update:modelValue', $event)"
    @blur="emit('blur', $event)"
  />
</template>
