import { describe, expect, it } from 'vitest'
import dialogSource from './components/Dialog.vue?raw'
import drawerSource from './components/Drawer.vue?raw'
import inputSource from './components/Input.vue?raw'
import maskedSource from './components/MaskedValue.vue?raw'
import revealSource from './components/StepUpReveal.vue?raw'
import toastSource from './components/Toast.vue?raw'
import shellSource from './layout/PortalShell.vue?raw'
import focusSource from './useModalFocus.ts?raw'

describe('PHASE-07 component accessibility contracts', () => {
  it('keeps field errors connected to the native control', () => {
    expect(inputSource).toContain(':aria-invalid')
    expect(inputSource).toContain(':aria-describedby')
  })

  it('exposes modal, focus containment and live-region semantics', () => {
    expect(dialogSource).toContain('role="dialog"')
    expect(drawerSource).toContain('aria-modal="true"')
    expect(dialogSource).toContain('useModalFocus')
    expect(focusSource).toContain("event.key !== 'Tab'")
    expect(focusSource).toContain('previousFocus')
    expect(toastSource).toContain(':aria-live="liveMode"')
  })

  it('does not mount revealed sensitive content before external authorization', () => {
    expect(maskedSource).toContain("revealed: false")
    expect(revealSource).toContain('v-if="revealed" name="revealed"')
    expect(revealSource).toContain('v-else name="masked"')
    expect(revealSource).toContain("defineEmits<{ requestReveal: []; conceal: [] }>()")
    expect(revealSource).not.toContain('local' + 'Storage')
  })

  it('provides skip-link and semantic main content in the shared shell', () => {
    expect(shellSource).toContain('sgj-skip-link')
    expect(shellSource).toContain('<main')
  })
})
