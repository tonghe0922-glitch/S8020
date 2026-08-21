import type { Component } from 'vue'
import type { BusinessAudience } from '../platform/portal-config'
import P001IdentityPage from '../platform/pages/P001IdentityPage.vue'
import P002PermissionRequestPage from '../platform/pages/P002PermissionRequestPage.vue'
import P003ProfileChangePage from '../platform/pages/P003ProfileChangePage.vue'
import P004GenericRequestPage from '../platform/pages/P004GenericRequestPage.vue'
import P005NoticePage from '../platform/pages/P005NoticePage.vue'
import P006MeetingPage from '../platform/pages/P006MeetingPage.vue'
import P007ShiftPage from '../platform/pages/P007ShiftPage.vue'
import Phase09CenterInboxPage from '../platform/pages/Phase09CenterInboxPage.vue'
import Phase09TechWorkflowMonitorPage from '../platform/pages/Phase09TechWorkflowMonitorPage.vue'
import Phase10TechMonitorPage from '../platform/pages/Phase10TechMonitorPage.vue'
import P008LeaveChangeCenterPage from '../platform/pages/phase10/P008LeaveChangeCenterPage.vue'
import P008LeaveChangePage from '../platform/pages/phase10/P008LeaveChangePage.vue'
import P008LeaveQuotaLedgerPage from '../platform/pages/phase10/P008LeaveQuotaLedgerPage.vue'
import P008LeaveRequestPage from '../platform/pages/phase10/P008LeaveRequestPage.vue'
import P008LeaveReviewPage from '../platform/pages/phase10/P008LeaveReviewPage.vue'
import P008QuotaManagementPage from '../platform/pages/phase10/P008QuotaManagementPage.vue'
import P009HrReviewPage from '../platform/pages/phase10/P009HrReviewPage.vue'
import P009OvertimeManagementPage from '../platform/pages/phase10/P009OvertimeManagementPage.vue'
import P009OvertimeRequestPage from '../platform/pages/phase10/P009OvertimeRequestPage.vue'
import P009PayrollBasisPage from '../platform/pages/phase10/P009PayrollBasisPage.vue'
import P009ResultAcceptancePage from '../platform/pages/phase10/P009ResultAcceptancePage.vue'
import P009TimeOffRequestPage from '../platform/pages/phase10/P009TimeOffRequestPage.vue'
import P010LearningManagementPage from '../platform/pages/phase10/P010LearningManagementPage.vue'
import P010LearningTasksPage from '../platform/pages/phase10/P010LearningTasksPage.vue'
import P010OnlineExamPage from '../platform/pages/phase10/P010OnlineExamPage.vue'
import P010PermissionLinkagePage from '../platform/pages/phase10/P010PermissionLinkagePage.vue'
import P010PracticalCertificationPage from '../platform/pages/phase10/P010PracticalCertificationPage.vue'
import P010PracticalTaskPage from '../platform/pages/phase10/P010PracticalTaskPage.vue'
import P010QualificationsPage from '../platform/pages/phase10/P010QualificationsPage.vue'
import P011CenterPage from '../platform/pages/phase11/P011CenterPage.vue'
import P011EmployeePage from '../platform/pages/phase11/P011EmployeePage.vue'
import P011TechPage from '../platform/pages/phase11/P011TechPage.vue'
import P012CenterPage from '../platform/pages/phase11/P012CenterPage.vue'
import P012EmployeePage from '../platform/pages/phase11/P012EmployeePage.vue'
import P012TechPage from '../platform/pages/phase11/P012TechPage.vue'
import P013CenterPage from '../platform/pages/phase11/P013CenterPage.vue'
import P013EmployeePage from '../platform/pages/phase11/P013EmployeePage.vue'
import P013TechPage from '../platform/pages/phase11/P013TechPage.vue'

export interface PortalRouteSpec {
  audience: BusinessAudience
  path: string
  name: string
  component: Component
  props?: Readonly<Record<string, unknown>>
  permission?: string
  permissionsAny?: readonly string[]
}

const PHASE09_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/13/04/04', name: 'p001-mfa',
    component: P001IdentityPage,
  },
  {
    audience: 'self', path: '/employee/13/04/06', name: 'p001-sessions',
    component: P001IdentityPage,
  },
  {
    audience: 'tech', path: '/tech/03/01/01', name: 'p001-security-monitor',
    component: P001IdentityPage, permission: 'p001.session.monitor',
  },
  {
    audience: 'self', path: '/employee/03/07/04',
    name: 'p002-temporary-permission-request', component: P002PermissionRequestPage,
    props: { mode: 'employee', requestKind: 'TEMPORARY_PERMISSION' },
    permissionsAny: ['p002.request.submit', 'p002.request.read'],
  },
  {
    audience: 'self', path: '/employee/03/07/05',
    name: 'p002-project-permission-request', component: P002PermissionRequestPage,
    props: { mode: 'employee', requestKind: 'PROJECT_PERMISSION' },
    permissionsAny: ['p002.request.submit', 'p002.request.read'],
  },
  {
    audience: 'tech', path: '/tech/03/01/04', name: 'p002-permission-execution',
    component: P002PermissionRequestPage, props: { mode: 'tech' },
    permissionsAny: ['p002.request.read', 'p002.request.execute', 'p002.request.revoke'],
  },
  {
    audience: 'self', path: '/employee/03/03/01', name: 'p003-profile-change',
    component: P003ProfileChangePage, props: { mode: 'employee' },
    permissionsAny: ['p003.change.submit', 'p003.change.read'],
  },
  {
    audience: 'center', path: '/center/03/02/01', name: 'p003-profile-roster',
    component: P003ProfileChangePage, props: { mode: 'center' },
    permissionsAny: ['p003.change.read', 'p003.change.review'],
  },
  {
    audience: 'tech', path: '/tech/04/01/01', name: 'p003-profile-sync-monitor',
    component: P003ProfileChangePage, props: { mode: 'tech' },
    permissionsAny: ['p003.change.read', 'p003.change.apply'],
  },
  {
    audience: 'self', path: '/employee/03/07/01', name: 'p004-generic-request',
    component: P004GenericRequestPage, props: { mode: 'employee' },
    permissionsAny: ['p004.request.submit', 'p004.request.read'],
  },
  {
    audience: 'self', path: '/employee/13/01/05', name: 'p005-notice-receipt',
    component: P005NoticePage, props: { mode: 'employee' },
    permissionsAny: ['p005.notice.read', 'p005.notice.receipt'],
  },
  {
    audience: 'center', path: '/center/13/01/05', name: 'p005-notice-publish',
    component: P005NoticePage, props: { mode: 'center' },
    permissionsAny: ['p005.notice.publish', 'p005.notice.manage'],
  },
  {
    audience: 'center', path: '/center/02/01/01', name: 'phase09-center-inbox',
    component: Phase09CenterInboxPage,
    permissionsAny: [
      'p001.session.monitor', 'p002.request.read', 'p002.request.review',
      'p003.change.read', 'p003.change.review', 'p004.request.read', 'p004.request.act',
    ],
  },
  {
    audience: 'tech', path: '/tech/05/03/01', name: 'p004-workflow-instance-monitor',
    component: Phase09TechWorkflowMonitorPage,
    permissionsAny: [
      'p004.request.read', 'p005.notice.monitor', 'p006.meeting.monitor',
      'p007.schedule.monitor', 'p008.leave.monitor', 'p009.overtime.monitor',
      'p010.learning.monitor',
    ],
  },
]

const P006_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/05/01/03', name: 'p006-meeting-detail',
    component: P006MeetingPage, props: { mode: 'employee' },
    permissionsAny: ['p006.meeting.read', 'p006.meeting.action'],
  },
  {
    audience: 'self', path: '/employee/05/07/02', name: 'p006-action-items',
    component: P006MeetingPage, props: { mode: 'employee' },
    permissionsAny: ['p006.meeting.read', 'p006.meeting.action'],
  },
  {
    audience: 'center', path: '/center/06/09/03', name: 'p006-meeting-management',
    component: P006MeetingPage, props: { mode: 'center' },
    permissionsAny: ['p006.meeting.create', 'p006.meeting.manage', 'p006.meeting.accept'],
  },
  {
    audience: 'center', path: '/center/05/02/02', name: 'p006-action-ledger',
    component: P006MeetingPage, props: { mode: 'center' },
    permissionsAny: ['p006.meeting.read', 'p006.meeting.manage', 'p006.meeting.accept'],
  },
]

const P007_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/04/01/01', name: 'p007-my-schedule',
    component: P007ShiftPage, props: { mode: 'employee' },
    permissionsAny: ['p007.schedule.read', 'p007.schedule.change'],
  },
  {
    audience: 'self', path: '/employee/03/01/09', name: 'p007-shift-change',
    component: P007ShiftPage, props: { mode: 'employee' },
    permissionsAny: ['p007.schedule.read', 'p007.schedule.change'],
  },
  {
    audience: 'self', path: '/employee/03/01/10', name: 'p007-shift-change-status',
    component: P007ShiftPage, props: { mode: 'employee' },
    permissionsAny: ['p007.schedule.read', 'p007.schedule.change'],
  },
  {
    audience: 'center', path: '/center/04/01/01', name: 'p007-schedule-management',
    component: P007ShiftPage, props: { mode: 'center' },
    permissionsAny: ['p007.schedule.manage', 'p007.schedule.change'],
  },
  {
    audience: 'center', path: '/center/04/07/04', name: 'p007-shift-review',
    component: P007ShiftPage, props: { mode: 'center' },
    permissionsAny: ['p007.schedule.review', 'p007.schedule.manage'],
  },
  {
    audience: 'center', path: '/center/04/07/05', name: 'p007-shift-ledger',
    component: P007ShiftPage, props: { mode: 'center' },
    permissionsAny: ['p007.schedule.read', 'p007.schedule.manage'],
  },
]

const P008_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/03/01/01', name: 'p008-leave-request',
    component: P008LeaveRequestPage,
    permissionsAny: ['p008.leave.submit', 'p008.leave.read'],
  },
  {
    audience: 'self', path: '/employee/04/03/02', name: 'p008-quota-ledger',
    component: P008LeaveQuotaLedgerPage,
    permissionsAny: ['p008.leave.submit', 'p008.leave.read'],
  },
  {
    audience: 'self', path: '/employee/03/01/02', name: 'p008-leave-change',
    component: P008LeaveChangePage,
    permissionsAny: ['p008.leave.submit', 'p008.leave.read'],
  },
  {
    audience: 'center', path: '/center/04/04/01', name: 'p008-leave-review',
    component: P008LeaveReviewPage,
    permissionsAny: ['p008.leave.read', 'p008.leave.review', 'p008.leave.manage'],
  },
  {
    audience: 'center', path: '/center/04/04/03', name: 'p008-quota-management',
    component: P008QuotaManagementPage,
    permissionsAny: ['p008.leave.read', 'p008.leave.review', 'p008.leave.manage'],
  },
  {
    audience: 'center', path: '/center/04/04/06', name: 'p008-leave-change-center',
    component: P008LeaveChangeCenterPage,
    permissionsAny: ['p008.leave.read', 'p008.leave.review', 'p008.leave.manage'],
  },
]

const P009_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/03/01/06', name: 'p009-overtime-request',
    component: P009OvertimeRequestPage,
    permissionsAny: ['p009.overtime.submit', 'p009.overtime.read'],
  },
  {
    audience: 'self', path: '/employee/03/01/07', name: 'p009-time-off-request',
    component: P009TimeOffRequestPage,
    permissionsAny: ['p009.overtime.submit', 'p009.overtime.read'],
  },
  {
    audience: 'self', path: '/employee/04/04/02', name: 'p009-result-acceptance',
    component: P009ResultAcceptancePage,
    permissionsAny: ['p009.overtime.submit', 'p009.overtime.read'],
  },
  {
    audience: 'center', path: '/center/04/05/01', name: 'p009-overtime-management',
    component: P009OvertimeManagementPage,
    permissionsAny: [
      'p009.overtime.read', 'p009.overtime.review',
      'p009.overtime.hr', 'p009.overtime.manage',
    ],
  },
  {
    audience: 'center', path: '/center/04/05/05', name: 'p009-hr-review',
    component: P009HrReviewPage,
    permissionsAny: [
      'p009.overtime.read', 'p009.overtime.review',
      'p009.overtime.hr', 'p009.overtime.manage',
    ],
  },
  {
    audience: 'center', path: '/center/04/05/06', name: 'p009-payroll-basis',
    component: P009PayrollBasisPage,
    permissionsAny: [
      'p009.overtime.read', 'p009.overtime.review',
      'p009.overtime.hr', 'p009.overtime.manage',
    ],
  },
]

const P010_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/07/01/01', name: 'p010-learning-tasks',
    component: P010LearningTasksPage,
    permissionsAny: ['p010.learning.read', 'p010.learning.complete', 'p010.learning.exam'],
  },
  {
    audience: 'self', path: '/employee/07/04/02', name: 'p010-online-exam',
    component: P010OnlineExamPage,
    permissionsAny: ['p010.learning.read', 'p010.learning.complete', 'p010.learning.exam'],
  },
  {
    audience: 'self', path: '/employee/07/05/01', name: 'p010-practical-task',
    component: P010PracticalTaskPage,
    permissionsAny: ['p010.learning.read', 'p010.learning.complete', 'p010.learning.exam'],
  },
  {
    audience: 'self', path: '/employee/07/06/01', name: 'p010-qualifications',
    component: P010QualificationsPage,
    permissionsAny: ['p010.learning.read', 'p010.learning.complete', 'p010.learning.exam'],
  },
  {
    audience: 'center', path: '/center/06/03/07', name: 'p010-learning-management',
    component: P010LearningManagementPage,
    permissionsAny: ['p010.learning.manage', 'p010.learning.certify', 'p010.learning.read'],
  },
  {
    audience: 'center', path: '/center/10/08/03', name: 'p010-practical-certification',
    component: P010PracticalCertificationPage,
    permissionsAny: ['p010.learning.manage', 'p010.learning.certify', 'p010.learning.read'],
  },
  {
    audience: 'center', path: '/center/10/08/07', name: 'p010-permission-linkage',
    component: P010PermissionLinkagePage,
    permissionsAny: ['p010.learning.manage', 'p010.learning.certify', 'p010.learning.read'],
  },
]


const P011_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/08/01/01', name: 'p011-performance-self',
    component: P011EmployeePage,
    permissionsAny: ['p011.performance.self', 'p011.performance.read'],
  },
  {
    audience: 'center', path: '/center/10/01/01', name: 'p011-performance-management',
    component: P011CenterPage,
    permissionsAny: [
      'p011.performance.create', 'p011.performance.evaluate',
      'p011.performance.calibrate', 'p011.performance.appeal',
      'p011.performance.impact',
    ],
  },
  {
    audience: 'tech', path: '/tech/06/05/01', name: 'p011-performance-monitor',
    component: P011TechPage, permission: 'p011.performance.monitor',
  },
]


const P012_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/03/03/05', name: 'p012-promotion-self',
    component: P012EmployeePage,
    permissionsAny: ['p012.promotion.create', 'p012.promotion.read'],
  },
  {
    audience: 'center', path: '/center/10/06/01', name: 'p012-promotion-management',
    component: P012CenterPage,
    permissionsAny: [
      'p012.promotion.create', 'p012.promotion.review',
      'p012.promotion.appoint', 'p012.promotion.activate',
    ],
  },
  {
    audience: 'tech', path: '/tech/01/11/07', name: 'p012-promotion-monitor',
    component: P012TechPage, permission: 'p012.promotion.monitor',
  },
]


const P013_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'self', path: '/employee/08/07/02', name: 'p013-reward-self',
    component: P013EmployeePage,
    permissionsAny: ['p013.reward.create', 'p013.reward.read'],
  },
  {
    audience: 'center', path: '/center/10/10/02', name: 'p013-reward-management',
    component: P013CenterPage,
    permissionsAny: ['p013.reward.create', 'p013.reward.review', 'p013.reward.execute'],
  },
  {
    audience: 'tech', path: '/tech/06/06/01', name: 'p013-reward-monitor',
    component: P013TechPage, permission: 'p013.reward.monitor',
  },
]

const PHASE10_TECH_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  {
    audience: 'tech', path: '/tech/07/11/01',
    name: 'p008-p009-attendance-integration-monitor', component: Phase10TechMonitorPage,
    props: { processes: ['P008', 'P009'] },
    permissionsAny: ['p008.leave.monitor', 'p009.overtime.monitor'],
  },
  {
    audience: 'tech', path: '/tech/07/09/01',
    name: 'p009-payroll-integration-monitor', component: Phase10TechMonitorPage,
    props: { processes: ['P009'] }, permission: 'p009.overtime.monitor',
  },
  {
    audience: 'tech', path: '/tech/03/03/09',
    name: 'p010-role-permission-audit', component: Phase10TechMonitorPage,
    props: { processes: ['P010'] }, permission: 'p010.learning.monitor',
  },
]

export const PORTAL_ROUTE_SPECS: readonly PortalRouteSpec[] = [
  ...PHASE09_ROUTE_SPECS,
  ...P006_ROUTE_SPECS,
  ...P007_ROUTE_SPECS,
  ...P008_ROUTE_SPECS,
  ...P009_ROUTE_SPECS,
  ...P010_ROUTE_SPECS,
  ...PHASE10_TECH_ROUTE_SPECS,
  ...P011_ROUTE_SPECS,
  ...P012_ROUTE_SPECS,
  ...P013_ROUTE_SPECS,
]
