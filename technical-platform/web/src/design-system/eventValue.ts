export function eventValue(event: Event): string {
  const target = event.target
  if (!target || typeof target !== 'object' || !('value' in target)) return ''
  const value = (target as { value?: unknown }).value
  return typeof value === 'string' ? value : ''
}
