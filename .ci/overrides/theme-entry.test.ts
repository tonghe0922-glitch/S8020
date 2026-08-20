import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function readSource(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8')
}

const createPortalAppSource = readSource('../platform/create-portal-app.ts')
const styleEntry = readSource('../styles.css')
const tokenSource = readSource('./tokens.css')
const shellSource = readSource('../shared/layout/rebuild/rebuild-shell.css')
const navigationSource = readSource('../router/PortalNavigation.vue')
const sessionHeaderSource = readSource('../platform/PortalSessionHeader.vue')

describe('current two-port design-system entry', () => {
  it('loads the global style entry for every portal application', () => {
    expect(createPortalAppSource).toContain("import '../styles.css'")
  })

  it('loads canonical design layers and the rebuilt shell in deterministic order', () => {
    const expectedImports = [
      './design-system/tokens.css',
      './design-system/base.css',
      './design-system/components.css',
      './design-system/templates.css',
      './design-system/extended.css',
      './shared/layout/rebuild/rebuild-shell.css',
    ]
    let previousIndex = -1
    for (const path of expectedImports) {
      const currentIndex = styleEntry.indexOf(path)
      expect(currentIndex, `${path} must be imported`).toBeGreaterThan(previousIndex)
      previousIndex = currentIndex
    }
  })

  it('uses the approved warm amber-orange reference instead of the old indigo token set', () => {
    expect(tokenSource).toContain('--sgj-brand-600: #ea580c')
    expect(tokenSource).toContain('--sgj-canvas: #f4f4f7')
    expect(tokenSource).not.toContain('#4f46e5')
  })

  it('keeps desktop, drawer and mobile-bottom-navigation shell contracts', () => {
    expect(shellSource).toContain('.rebuild-shell__sidebar')
    expect(shellSource).toContain('.rebuild-shell--drawer-open')
    expect(shellSource).toContain('.rebuild-shell__bottom-nav')
  })

  it('keeps navigation active-state and session identity visuals inside their components', () => {
    expect(navigationSource).toContain("import NavigationIcon from './NavigationIcon.vue'")
    expect(navigationSource).toContain('.portal-navigation :deep(svg)')
    expect(navigationSource).toContain('.portal-navigation__child-link.is-active')
    expect(sessionHeaderSource).toContain('portal-session-header__avatar')
    expect(sessionHeaderSource).toContain(
      'linear-gradient(135deg, var(--sgj-brand-400), var(--sgj-brand-600))',
    )
  })
})
