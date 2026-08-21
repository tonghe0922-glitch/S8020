import type { RuntimePortCode } from '../platform/portal-config'
import {
  NAVIGATION_CATALOG,
  type NavigationCatalogItem,
  type NavigationCatalogSection,
  type NavigationIconKey,
} from './navigation-catalog'

export type ProjectedNavigationState = 'implemented' | 'developing' | 'unauthorized'

export interface NavigationRouteAccessRule {
  readonly all?: readonly string[]
  readonly any?: readonly string[]
}

export interface NavigationProjectionOptions {
  readonly runtimeCode: RuntimePortCode
  readonly permissions: ReadonlySet<string>
  readonly implementedRoutePaths: ReadonlySet<string>
  readonly routeAccessRules?: ReadonlyMap<string, NavigationRouteAccessRule>
  readonly mobile: boolean
  readonly identityLabel?: string | null
  readonly catalog?: readonly NavigationCatalogSection[]
}

export interface ActiveNavigationItem {
  readonly sourceKey: string
  readonly label: string
  readonly routePath: string
  readonly limited: boolean
}

export interface ProjectedNavigationItem {
  readonly key: string
  readonly label: string
  readonly groupKey: string
  readonly groupLabel: string
  readonly routePath: string
  readonly sourceRoutePath: string | null
  readonly state: ProjectedNavigationState
  readonly limited: boolean
}

export interface ProjectedNavigationGroup {
  readonly key: string
  readonly label: string
  readonly iconKey: NavigationIconKey
  readonly items: readonly ProjectedNavigationItem[]
  readonly state: ProjectedNavigationState
  readonly mobileAccess: 'primary' | 'more'
  readonly centerScoped: boolean
  readonly contextLabel: string | null
}

export interface ProjectedMobileNavigationGroups {
  readonly primary: readonly ProjectedNavigationGroup[]
  readonly overflow: readonly ProjectedNavigationGroup[]
}

function routeAccessSatisfied(
  rule: NavigationRouteAccessRule | undefined,
  granted: ReadonlySet<string>,
): boolean {
  if (rule?.all?.length && !rule.all.every((permission) => granted.has(permission))) return false
  if (rule?.any?.length && !rule.any.some((permission) => granted.has(permission))) return false
  return true
}

function placeholderPath(
  state: Exclude<ProjectedNavigationState, 'implemented'>,
  item: NavigationCatalogItem,
  section: NavigationCatalogSection,
): string {
  const path = state === 'unauthorized' ? '/forbidden' : '/developing'
  const query = new URLSearchParams({
    module: item.key,
    label: item.label,
    group: section.label,
  })
  return `${path}?${query.toString()}`
}

function itemState(
  routePath: string | undefined,
  options: NavigationProjectionOptions,
): ProjectedNavigationState {
  if (!routePath || !options.implementedRoutePaths.has(routePath)) return 'developing'
  const rule = options.routeAccessRules?.get(routePath)
  return routeAccessSatisfied(rule, options.permissions) ? 'implemented' : 'unauthorized'
}

function projectItem(
  item: NavigationCatalogItem,
  section: NavigationCatalogSection,
  options: NavigationProjectionOptions,
): ProjectedNavigationItem {
  const state = itemState(item.routePath, options)
  const sourceRoutePath = state === 'developing' ? null : item.routePath ?? null
  return {
    key: item.key,
    label: item.label,
    groupKey: section.key,
    groupLabel: section.label,
    routePath: state === 'implemented'
      ? item.routePath ?? '/'
      : placeholderPath(state, item, section),
    sourceRoutePath,
    state,
    limited: options.mobile && section.mobileAccess === 'more',
  }
}

function groupState(items: readonly ProjectedNavigationItem[]): ProjectedNavigationState {
  if (items.some((item) => item.state === 'implemented')) return 'implemented'
  if (items.some((item) => item.state === 'unauthorized')) return 'unauthorized'
  return 'developing'
}

function projectSection(
  section: NavigationCatalogSection,
  options: NavigationProjectionOptions,
): ProjectedNavigationGroup {
  const sourceItems = section.children?.length ? section.children : [section]
  const items = sourceItems.map((item) => projectItem(item, section, options))
  return {
    key: section.key,
    label: section.label,
    iconKey: section.iconKey,
    items,
    state: groupState(items),
    mobileAccess: section.mobileAccess,
    centerScoped: Boolean(section.centerScoped),
    contextLabel: section.centerScoped ? options.identityLabel?.trim() || null : null,
  }
}

export function projectNavigationGroups(
  options: NavigationProjectionOptions,
): ProjectedNavigationGroup[] {
  const catalog = options.catalog ?? NAVIGATION_CATALOG
  return catalog
    .filter((section) => section.ports.includes(options.runtimeCode))
    .map((section) => projectSection(section, options))
}

export function flattenProjectedNavigation(
  groups: readonly ProjectedNavigationGroup[],
): ProjectedNavigationItem[] {
  return groups.flatMap((group) => group.items)
}

export function projectActiveNavigation(
  options: NavigationProjectionOptions,
): ActiveNavigationItem[] {
  return flattenProjectedNavigation(projectNavigationGroups(options))
    .filter((item) => item.state === 'implemented')
    .map((item) => ({
      sourceKey: item.key,
      label: item.label,
      routePath: item.routePath,
      limited: item.limited,
    }))
}

export function pathMatchesNavigationItem(currentPath: string, routePath: string): boolean {
  if (routePath === '/') return currentPath === '/'
  return currentPath === routePath || currentPath.startsWith(`${routePath}/`)
}

export function projectedItemIsActive(
  currentPath: string,
  currentModule: unknown,
  item: ProjectedNavigationItem,
): boolean {
  if (item.state === 'implemented' && item.sourceRoutePath) {
    return pathMatchesNavigationItem(currentPath, item.sourceRoutePath)
  }
  const placeholder = currentPath === '/developing' || currentPath === '/forbidden'
  return placeholder && currentModule === item.key
}

export function resolveActiveItem(
  groups: readonly ProjectedNavigationGroup[],
  currentPath: string,
  currentModule?: unknown,
): ProjectedNavigationItem | undefined {
  return flattenProjectedNavigation(groups)
    .filter((item) => projectedItemIsActive(currentPath, currentModule, item))
    .sort((left, right) => (right.sourceRoutePath?.length ?? 0) - (left.sourceRoutePath?.length ?? 0))[0]
}

export function resolveActiveGroup(
  groups: readonly ProjectedNavigationGroup[],
  currentPath: string,
  currentModule?: unknown,
): ProjectedNavigationGroup | undefined {
  const active = resolveActiveItem(groups, currentPath, currentModule)
  return active ? groups.find((group) => group.key === active.groupKey) : undefined
}

export function splitProjectedMobileNavigation(
  groups: readonly ProjectedNavigationGroup[],
  primaryLimit = 4,
): ProjectedMobileNavigationGroups {
  const preferred = groups.filter((group) => group.mobileAccess === 'primary')
  const primary = preferred.slice(0, Math.max(0, Math.trunc(primaryLimit)))
  const primaryKeys = new Set(primary.map((group) => group.key))
  return { primary, overflow: groups.filter((group) => !primaryKeys.has(group.key)) }
}
