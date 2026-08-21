import P014CenterPage from '../platform/pages/phase11/P014CenterPage.vue'
import P014EmployeePage from '../platform/pages/phase11/P014EmployeePage.vue'
import P014TechPage from '../platform/pages/phase11/P014TechPage.vue'
import type { PortalRouteSpec } from './portal-route-specs'

export const P014_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self',
    path: '/employee/02/03/09',
    name: 'p014-discipline-self-service',
    component: P014EmployeePage,
    permissionsAny: ['p014.discipline.read', 'p014.discipline.appeal'],
  },
  {
    audience: 'center',
    path: '/center/12/02/04',
    name: 'p014-discipline-management',
    component: P014CenterPage,
    permissionsAny: [
      'p014.discipline.create',
      'p014.discipline.read',
      'p014.discipline.investigate',
      'p014.discipline.decide',
      'p014.discipline.appeal',
      'p014.discipline.remediate',
    ],
  },
  {
    audience: 'tech',
    path: '/tech/06/06/02',
    name: 'p014-discipline-monitor',
    component: P014TechPage,
    permission: 'p014.discipline.monitor',
  },
]
