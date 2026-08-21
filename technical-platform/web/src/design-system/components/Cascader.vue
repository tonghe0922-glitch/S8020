<script setup lang="ts">
import { computed } from 'vue'
import { eventValue } from '../eventValue'
import type { CascaderOption } from '../types'
import { useFieldA11y } from '../useFieldA11y'
import FieldFrame from './FieldFrame.vue'

const props = withDefaults(defineProps<{
  id?: string
  label: string
  modelValue?: readonly string[]
  options: readonly CascaderOption[]
  placeholder?: string
  hint?: string
  error?: string
  required?: boolean
  disabled?: boolean
}>(), {
  modelValue: () => [],
  placeholder: '请选择',
  required: false,
  disabled: false,
})

const emit = defineEmits<{ 'update:modelValue': [value: string[]]; change: [value: string[]] }>()
const field = useFieldA11y({ id: () => props.id, hint: () => props.hint, error: () => props.error, prefix: 'sgj-cascader' })

const levels = computed(() => {
  const result: Array<readonly CascaderOption[]> = []
  let options = props.options
  let depth = 0
  while (options.length > 0) {
    result.push(options)
    const selected = options.find((option) => option.value === props.modelValue[depth])
    if (!selected?.children?.length) break
    options = selected.children
    depth += 1
  }
  return result
})

function updateLevel(depth: number, event: Event): void {
  const value = eventValue(event)
  const next = props.modelValue.slice(0, depth)
  if (value) next.push(value)
  emit('update:modelValue', next)
  emit('change', next)
}
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
    <div class="sgj-cascader">
      <select
        v-for="(level, depth) in levels"
        :id="depth === 0 ? field.controlId.value : `${field.controlId.value}-${depth}`"
        :key="depth"
        class="sgj-control sgj-control--select"
        :value="modelValue[depth] ?? ''"
        :required="required && depth === 0"
        :disabled="disabled"
        :aria-label="depth === 0 ? undefined : `${label}第${depth + 1}级`"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="field.describedBy.value"
        @change="updateLevel(depth, $event)"
      >
        <option value="">{{ placeholder }}</option>
        <option v-for="option in level" :key="option.value" :value="option.value" :disabled="option.disabled">
          {{ option.label }}
        </option>
      </select>
    </div>
  </FieldFrame>
</template>
