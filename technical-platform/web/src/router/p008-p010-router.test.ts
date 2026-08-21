import { createMemoryHistory, type Router } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { PORTALS, type PortalDefinition } from '../platform/portal-config'
import { createPortalRouter, type PortalRouterSession } from './portal-router'

class Session implements PortalRouterSession {
  authenticated = true
  readonly permissions = new Set<string>()

  restore(): Promise<boolean> {
    return Promise.resolve(true)
  }

  can(permission: string): boolean {
    return this.permissions.has(permission)
  }
}

type ExpectedRoute = readonly [path: string, name: string]

const P008_EMPLOYEE: readonly ExpectedRoute[] = [
  ['/employee/03/01/01', 'p008-leave-request'],
  ['/employee/04/03/02', 'p008-quota-ledger'],
  ['/employee/03/01/02', 'p008-leave-change'],
]
const P008_CENTER: readonly ExpectedRoute[] = [
  ['/center/04/04/01', 'p008-leave-review'],
  ['/center/04/04/03', 'p008-quota-management'],
  ['/center/04/04/06', 'p008-leave-change-center'],
]
const P009_EMPLOYEE: readonly ExpectedRoute[] = [
  ['/employee/03/01/06', 'p009-overtime-request'],
  ['/employee/03/01/07', 'p009-time-off-request'],
  ['/employee/04/04/02', 'p009-result-acceptance'],
]
const P009_CENTER: readonly ExpectedRoute[] = [
  ['/center/04/05/01', 'p009-overtime-management'],
  ['/center/04/05/05', 'p009-hr-review'],
  ['/center/04/05/06', 'p009-payroll-basis'],
]
const P010_EMPLOYEE: readonly ExpectedRoute[] = [
  ['/employee/07/01/01', 'p010-learning-tasks'],
  ['/employee/07/04/02', 'p010-online-exam'],
  ['/employee/07/05/01', 'p010-practical-task'],
  ['/employee/07/06/01', 'p010-qualifications'],
]
const P010_CENTER: readonly ExpectedRoute[] = [
  ['/center/06/03/07', 'p010-learning-management'],
  ['/center/10/08/03', 'p010-practical-certification'],
  ['/center/10/08/07', 'p010-permission-linkage'],
]

function routerFor(portal: PortalDefinition, permission: string): Router {
  const session = new Session()
  session.permissions.add(permission)
  return createPortalRouter(portal, session, createMemoryHistory())
}

async function expectRoutes(
  portal: PortalDefinition,
  permission: string,
  routes: readonly ExpectedRoute[],
): Promise<void> {
  const router = routerFor(portal, permission)
  for (const [path, name] of routes) {
    await router.push(path)
    expect(router.currentRoute.value.name).toBe(name)
  }
}

function routeComponents(router: Router, routes: readonly ExpectedRoute[]) {
  return routes.map(([, name]) => {
    const route = router.getRoutes().find((candidate) => candidate.name === name)
    expect(route, `missing route ${name}`).toBeDefined()
    return route?.components?.default
  })
}

function expectDistinctComponents(
  portal: PortalDefinition,
  permission: string,
  routes: readonly ExpectedRoute[],
): void {
  const components = routeComponents(routerFor(portal, permission), routes)
  expect(new Set(components).size).toBe(routes.length)
}

describe('PHASE-10 P008-P010 source-bound routes', () => {
  it('registers all 19 employee and center business pages', async () => {
    await expectRoutes(PORTALS.work, 'p008.leave.read', P008_EMPLOYEE)
    await expectRoutes(PORTALS.work, 'p008.leave.manage', P008_CENTER)
    await expectRoutes(PORTALS.work, 'p009.overtime.read', P009_EMPLOYEE)
    await expectRoutes(PORTALS.work, 'p009.overtime.manage', P009_CENTER)
    await expectRoutes(PORTALS.work, 'p010.learning.read', P010_EMPLOYEE)
    await expectRoutes(PORTALS.work, 'p010.learning.manage', P010_CENTER)
  })

  it('uses a distinct component for every P008-P010 business route', () => {
    expectDistinctComponents(PORTALS.work, 'p008.leave.read', P008_EMPLOYEE)
    expectDistinctComponents(PORTALS.work, 'p008.leave.manage', P008_CENTER)
    expectDistinctComponents(PORTALS.work, 'p009.overtime.read', P009_EMPLOYEE)
    expectDistinctComponents(PORTALS.work, 'p009.overtime.manage', P009_CENTER)
    expectDistinctComponents(PORTALS.work, 'p010.learning.read', P010_EMPLOYEE)
    expectDistinctComponents(PORTALS.work, 'p010.learning.manage', P010_CENTER)
  })

  it('keeps technical routes monitor-only', async () => {
    await expectRoutes(PORTALS.tech, 'p010.learning.monitor', [
      ['/tech/05/03/01', 'p004-workflow-instance-monitor'],
      ['/tech/03/03/09', 'p010-role-permission-audit'],
    ])
    await expectRoutes(PORTALS.tech, 'p008.leave.monitor', [
      ['/tech/07/11/01', 'p008-p009-attendance-integration-monitor'],
    ])
    await expectRoutes(PORTALS.tech, 'p009.overtime.monitor', [
      ['/tech/07/09/01', 'p009-payroll-integration-monitor'],
    ])
  })
})
