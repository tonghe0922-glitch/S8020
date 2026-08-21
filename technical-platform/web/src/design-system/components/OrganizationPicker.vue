<script setup lang="ts">
import type { SelectOption } from '../types'
import Select from './Select.vue'

withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: string
  options: readonly SelectOption[]
  placeholder?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  modelValue: '',
  placeholder: '请选择组织',
  required: false,
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>()
</script>

<template>
  <Select
    :id="id"
    :label="label"
    :model-value="modelValue"
    :options="options"
    :placeholder="placeholder"
    :hint="hint"
    :error="error"
    :required="required"
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event)"
    @change="emit('change', $event)"
  />
</template>
