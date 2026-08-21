import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

interface ModalFocusContract {
  panelRef: Ref<HTMLElement | null>
  trapFocus: (event: KeyboardEvent) => void
}

function focusableElements(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((element) => !element.hasAttribute('disabled') && element.tabIndex >= 0)
}

function loopFocus(panel: HTMLElement, event: KeyboardEvent): void {
  const elements = focusableElements(panel)
  if (elements.length === 0) {
    event.preventDefault()
    panel.focus({ preventScroll: true })
    return
  }
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus({ preventScroll: true })
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus({ preventScroll: true })
  }
}

export function useModalFocus(open: () => boolean): ModalFocusContract {
  const panelRef = ref<HTMLElement | null>(null)
  let previousFocus: HTMLElement | null = null

  function restoreFocus(): void {
    if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
    previousFocus = null
  }

  async function focusPanel(): Promise<void> {
    await nextTick()
    const panel = panelRef.value
    if (!panel) return
    const first = focusableElements(panel)[0] ?? panel
    first.focus({ preventScroll: true })
  }

  function trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab' || !panelRef.value) return
    loopFocus(panelRef.value, event)
  }

  watch(open, async (active) => {
    if (!active) {
      restoreFocus()
      return
    }
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await focusPanel()
  }, { immediate: true, flush: 'post' })

  onBeforeUnmount(restoreFocus)
  return { panelRef, trapFocus }
}
