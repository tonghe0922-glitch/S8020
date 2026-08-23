import OrgArchitectureManagePage from '../platform/pages/org/OrgArchitectureManagePage.vue'
import OrgArchitectureTechPage from '../platform/pages/org/OrgArchitectureTechPage.vue'
import OrgDirectoryPage from '../platform/pages/org/OrgDirectoryPage.vue'
import type { PortalRouteSpec } from './portal-route-specs'

export const ORG_ARCHITECTURE_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'tech',
    path: '/tech/org',
    name: 'org-architecture-tech',
    component: OrgArchitectureTechPage,
    permission: 'org.architecture.manage',
  },
  {
    audience: 'center',
    path: '/center/03/02/09',
    name: 'org-architecture-manage',
    component: OrgArchitectureManagePage,
    permissionsAny: [
      'org.architecture.edit',
      'org.architecture.review',
      'org.architecture.publish',
      'org.architecture.manage',
    ],
  },
  {
    audience: 'self',
    path: '/contacts/architecture',
    name: 'org-directory-architecture',
    component: OrgDirectoryPage,
    props: { mode: 'architecture' },
  },
  {
    audience: 'self',
    path: '/contacts/directory',
    name: 'org-directory-members',
    component: OrgDirectoryPage,
    props: { mode: 'directory' },
  },
]
