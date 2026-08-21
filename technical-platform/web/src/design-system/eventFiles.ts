export function eventFiles(event: Event): File[] {
  if (!(event.target instanceof HTMLInputElement) || !event.target.files) return []
  return Array.from(event.target.files)
}
