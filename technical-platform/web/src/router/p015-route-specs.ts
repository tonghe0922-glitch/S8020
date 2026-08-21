import P015CenterPage from '../platform/pages/phase11/P015CenterPage.vue'
import P015EmployeePage from '../platform/pages/phase11/P015EmployeePage.vue'
import P015TechPage from '../platform/pages/phase11/P015TechPage.vue'
import type { PortalRouteSpec } from './portal-route-specs'

export const P015_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self',
    path: '/employee/08/06/04',
    name: 'p015-points-self-ledger',
    component: P015EmployeePage,
    permission: 'p015.points.read',
  },
  {
    audience: 'center',
    path: '/center/10/09/06',
    name: 'p015-points-management',
    component: P015CenterPage,
    permissionsAny: [
      'p015.points.create',
      'p015.points.read',
      'p015.points.review',
      'p015.points.reverse',
    ],
  },
  {
    audience: 'tech',
    path: '/tech/06/06/03',
    name: 'p015-points-monitor',
    component: P015TechPage,
    permission: 'p015.points.monitor',
  },
]
