import P016CenterPage from '../platform/pages/phase11/P016CenterPage.vue'
import P016EmployeePage from '../platform/pages/phase11/P016EmployeePage.vue'
import P016TechPage from '../platform/pages/phase11/P016TechPage.vue'
import type { PortalRouteSpec } from './portal-route-specs'

export const P016_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self',
    path: '/employee/03/06/05',
    name: 'p016-care-self-service',
    component: P016EmployeePage,
    permissionsAny: ['p016.care.create', 'p016.care.read', 'p016.care.confirm'],
  },
  {
    audience: 'center',
    path: '/center/06/03/09',
    name: 'p016-care-management',
    component: P016CenterPage,
    permissionsAny: [
      'p016.care.create',
      'p016.care.read',
      'p016.care.review',
      'p016.care.execute',
      'p016.care.reconcile',
    ],
  },
  {
    audience: 'tech',
    path: '/tech/01/11/04',
    name: 'p016-care-monitor',
    component: P016TechPage,
    permission: 'p016.care.monitor',
  },
]
