import {
  createRouter,
  createWebHashHistory,
  type RouteLocationNormalized,
  type RouteRecordRaw,
  type Router,
  type RouterHistory,
} from 'vue-router'
import AuthenticatedPortalLayout from '../platform/AuthenticatedPortalLayout.vue'
import PlatformShell from '../platform/PlatformShell.vue'
import type { PortalDefinition } from '../platform/portal-config'
import ForbiddenPage from '../platform/pages/ForbiddenPage.vue'
import LoginPage from '../platform/pages/LoginPage.vue'
import NavigationDevelopingPage from '../platform/pages/NavigationDevelopingPage.vue'
import NotFoundPage from '../platform/pages/NotFoundPage.vue'
import AuthzConfigurationPage from '../platform/pages/authz/AuthzConfigurationPage.vue'
import { clearRuntimeError, recordRuntimeError } from '../platform/runtime-error-state'
import { rotateNavigationAbortSignal } from './navigation-abort'
import { P014_ROUTE_SPECS } from './p014-route-specs'
import { P015_ROUTE_SPECS } from './p015-route-specs'
import { P016_ROUTE_SPECS } from './p016-route-specs'
import { PORTAL_ROUTE_SPECS, type PortalRouteSpec } from './portal-route-specs'
import { safeInternalRedirect } from './redirect'

export interface PortalRouterSession {
  readonly authenticated: boolean
  restore: () => Promise<boolean>
  can: (permission: string) => boolean
}

const ALL_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  ...PORTAL_ROUTE_SPECS,
  ...P014_ROUTE_SPECS,
  ...P015_ROUTE_SPECS,
  ...P016_ROUTE_SPECS,
]

function loginTarget(to: RouteLocationNormalized) {
  return { name: 'login', query: { redirect: safeInternalRedirect(to.fullPath) } }
}

async function restoreOnce(session: PortalRouterSession, state: { attempted: boolean }): Promise<void> {
  if (state.attempted || session.authenticated) return
  state.attempted = true
  try {
    await session.restore()
  } catch (cause) {
    recordRuntimeError(cause)
  }
}

function requiredPermission(to: RouteLocationNormalized): string | undefined {
  return typeof to.meta.permission === 'string' ? to.meta.permission : undefined
}

function requiredPermissionsAny(to: RouteLocationNormalized): string[] {
  if (!Array.isArray(to.meta.permissionsAny)) return []
  return to.meta.permissionsAny.filter((value): value is string => typeof value === 'string' && value.length > 0)
}

function authorizationRedirect(to: RouteLocationNormalized, session: PortalRouterSession) {
  const permission = requiredPermission(to)
  if (permission && !session.can(permission)) return { name: 'forbidden' }
  const anyPermissions = requiredPermissionsAny(to)
  if (anyPermissions.length && !anyPermissions.some((code) => session.can(code))) return { name: 'forbidden' }
  return undefined
}

function registerGuards(router: Router, session: PortalRouterSession): void {
  const state = { attempted: false }
  router.beforeEach(async (to) => {
    rotateNavigationAbortSignal()
    clearRuntimeError()
    await restoreOnce(session, state)
    if (to.meta.requiresAuth && !session.authenticated) return loginTarget(to)
    if (to.meta.guestOnly && session.authenticated) return safeInternalRedirect(to.query.redirect)
    return authorizationRedirect(to, session) ?? true
  })
  router.onError(recordRuntimeError)
}

function routeMeta(spec: PortalRouteSpec) {
  return {
    ...(spec.permission ? { permission: spec.permission } : {}),
    ...(spec.permissionsAny ? { permissionsAny: [...spec.permissionsAny] } : {}),
  }
}

function belongsToPort(spec: PortalRouteSpec, portal: PortalDefinition): boolean {
  return portal.code === 'tech' ? spec.audience === 'tech' : spec.audience !== 'tech'
}

function portalRoutes(portal: PortalDefinition): RouteRecordRaw[] {
  return ALL_ROUTE_SPECS.filter((spec) => belongsToPort(spec, portal)).map((spec) => ({
    path: spec.path,
    name: spec.name,
    component: spec.component,
    props: { portal, audience: spec.audience, ...spec.props },
    meta: routeMeta(spec),
  }))
}

function authzRoutes(portal: PortalDefinition): RouteRecordRaw[] {
  if (portal.code !== 'tech') return []
  return [
    {
      path: '/tech/authz/modules',
      name: 'authz-module-catalog',
      component: AuthzConfigurationPage,
      props: { mode: 'modules' },
      meta: { permissionsAny: ['authz.module.read', 'authz.config.manage'] },
    },
    {
      path: '/tech/authz/modules/:id',
      name: 'authz-module-permissions',
      component: AuthzConfigurationPage,
      props: { mode: 'module-permissions' },
      meta: { permissionsAny: ['authz.module.read', 'authz.module.manage', 'authz.config.manage'] },
    },
    {
      path: '/tech/authz/orgs/:orgId',
      name: 'authz-org-modules',
      component: AuthzConfigurationPage,
      props: { mode: 'org-modules' },
      meta: { permissionsAny: ['authz.org.module.manage', 'authz.config.manage'] },
    },
    {
      path: '/tech/authz/positions/:positionId',
      name: 'authz-position-roles',
      component: AuthzConfigurationPage,
      props: { mode: 'position-roles' },
      meta: { permissionsAny: ['authz.position.role.manage', 'authz.config.manage'] },
    },
    {
      path: '/tech/authz/preview',
      name: 'authz-config-preview',
      component: AuthzConfigurationPage,
      props: { mode: 'preview' },
      meta: { permissionsAny: ['authz.config.preview', 'authz.config.manage'] },
    },
  ]
}

function authenticatedRoutes(portal: PortalDefinition): RouteRecordRaw {
  return {
    path: '/',
    component: AuthenticatedPortalLayout,
    props: { portal },
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'portal-home',
        component: PlatformShell,
        props: { portal },
        meta: { pageTitle: portal.homeTitle },
      },
      ...portalRoutes(portal),
      ...authzRoutes(portal),
      {
        path: '/developing',
        name: 'navigation-developing',
        component: NavigationDevelopingPage,
        meta: { pageTitle: '正在开发中', navigationState: 'developing' },
      },
      {
        path: '/forbidden',
        name: 'forbidden',
        component: ForbiddenPage,
        props: { portal },
        meta: { pageTitle: '无访问权限' },
      },
    ],
  }
}

export function createPortalRouter(
  portal: PortalDefinition,
  session: PortalRouterSession,
  history: RouterHistory = createWebHashHistory(),
): Router {
  const router = createRouter({
    history,
    routes: [
      { path: '/login', name: 'login', component: LoginPage, props: { portal }, meta: { guestOnly: true } },
      authenticatedRoutes(portal),
      { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundPage, props: { portal } },
    ],
  })
  registerGuards(router, session)
  return router
}
