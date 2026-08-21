import { computed, useId, type ComputedRef } from 'vue'

interface FieldA11yOptions {
  readonly id: () => string | undefined
  readonly hint: () => string | undefined
  readonly error: () => string | undefined
  readonly prefix: string
}

interface FieldA11yContract {
  controlId: ComputedRef<string>
  hintId: ComputedRef<string>
  errorId: ComputedRef<string>
  describedBy: ComputedRef<string | undefined>
}

export function useFieldA11y(options: FieldA11yOptions): FieldA11yContract {
  const uid = useId()
  const controlId = computed(() => options.id()?.trim() || `${options.prefix}-${uid}`)
  const hintId = computed(() => `${controlId.value}-hint`)
  const errorId = computed(() => `${controlId.value}-error`)
  const describedBy = computed(() => {
    const ids = [options.hint() ? hintId.value : '', options.error() ? errorId.value : '']
    return ids.filter(Boolean).join(' ') || undefined
  })
  return { controlId, hintId, errorId, describedBy }
}
