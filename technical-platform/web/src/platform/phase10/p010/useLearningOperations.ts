import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  optionalIso,
  useAsyncActionState,
  useRecordSummary,
  type Phase10Action,
  type Phase10Aggregate,
  type PortalSessionStore,
} from '../shared'

export interface LearningForm {
  subject: string
  reason: string
  ownerEmployeeId: string
  contentVersion: string
  courseTeamName: string
  courseVersionId: string
  learnerProfile: string
  periodOrCourseNo: string
  riskLevel: string
  plannedStartAt: string
  plannedFinishAt: string
  completionRate: string
  score1000: string
  practicalResult: string
  note: string
  effectiveDate: string
  expireDate: string
}

interface LearningActionBody {
  expectedVersion: number
  note?: string
  effectiveDate?: string
  expireDate?: string
}

type LearningActivity = 'learning-progress' | 'exam' | 'practical'

const ENDPOINT = '/api/v1/processes/P010/assignments'
const CERTIFY_ACTIONS = new Set(['CERTIFY', 'RETURN_FOR_TRAINING'])

export const LEARNING_ACTIONS: Readonly<Record<string, readonly Phase10Action[]>> = {
  S01: [{ code: 'PUBLISH_CONTENT', label: '发布课程/制度版本' }],
  S02: [{ code: 'ASSIGN_BY_RISK', label: '按岗位风险指派' }],
  S06: [
    { code: 'CERTIFY', label: '认证通过' },
    { code: 'RETURN_FOR_TRAINING', label: '退回补训', tone: 'danger' },
  ],
  S07: [{ code: 'ACTIVATE_QUALIFICATION', label: '资格生效' }],
  S08: [{ code: 'LINK_PERMISSIONS', label: '联动岗位权限' }],
  S09: [{ code: 'COMPLETE_RETRAINING_CHECK', label: '完成复训/复证检查' }],
  S10: [{ code: 'ARCHIVE', label: '归档' }],
}

function createLearningForm(): LearningForm {
  return {
    subject: '', reason: '', ownerEmployeeId: '', contentVersion: '', courseTeamName: '',
    courseVersionId: '', learnerProfile: '', periodOrCourseNo: '', riskLevel: 'NORMAL',
    plannedStartAt: '', plannedFinishAt: '', completionRate: '100', score1000: '',
    practicalResult: '通过', note: '', effectiveDate: '', expireDate: '',
  }
}

function assertAssignment(form: LearningForm): void {
  const required = [
    form.subject, form.ownerEmployeeId, form.contentVersion, form.courseTeamName,
    form.courseVersionId, form.periodOrCourseNo,
  ]
  if (required.some((value) => !value.trim())) {
    throw new Error('请完整填写学习任务、目标员工和课程版本信息')
  }
}

function createBody(form: LearningForm, ownerCenterId: string) {
  if (!ownerCenterId) throw new Error('缺少中心身份')
  assertAssignment(form)
  return {
    subject: form.subject.trim(), reason: form.reason.trim() || null, ownerCenterId,
    ownerEmployeeId: form.ownerEmployeeId.trim(), contentVersion: form.contentVersion.trim(),
    courseTeamName: form.courseTeamName.trim(), courseVersionId: form.courseVersionId.trim(),
    learnerProfile: form.learnerProfile.trim() || null, periodOrCourseNo: form.periodOrCourseNo.trim(),
    riskLevel: form.riskLevel, plannedStartAt: optionalIso(form.plannedStartAt),
    plannedFinishAt: optionalIso(form.plannedFinishAt),
  }
}

function actionBody(form: LearningForm, aggregate: Phase10Aggregate): LearningActionBody {
  return {
    expectedVersion: aggregate.record.versionNo,
    note: form.note.trim() || undefined,
    effectiveDate: form.effectiveDate || undefined,
    expireDate: form.expireDate || undefined,
  }
}

function activityBody(form: LearningForm, activity: LearningActivity) {
  if (activity === 'learning-progress') {
    return { completionRate: Number(form.completionRate), note: form.note.trim() || null }
  }
  if (activity === 'exam') return { score1000: Number(form.score1000), note: form.note.trim() || null }
  return { result: form.practicalResult.trim(), note: form.note.trim() || null }
}

async function loadData(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
): Promise<void> {
  rows.value = await session.request<Phase10Aggregate[]>(ENDPOINT)
}

async function submitAssignment(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
  form: LearningForm,
  ownerCenterId: string,
  feedback: Ref<string>,
  resetForm: () => void,
): Promise<void> {
  await session.request(ENDPOINT, {
    method: 'POST', idempotencyKey: idempotencyKey('P010', 'create'),
    body: createBody(form, ownerCenterId),
  })
  feedback.value = '学习任务已创建，等待课程版本发布'
  resetForm()
  await loadData(session, rows)
}

async function executeAction(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
  form: LearningForm,
  aggregate: Phase10Aggregate,
  action: string,
  feedback: Ref<string>,
  resetForm: () => void,
): Promise<void> {
  await session.request(`${ENDPOINT}/${aggregate.record.id}/actions/${action}`, {
    method: 'POST', idempotencyKey: idempotencyKey('P010', action.toLowerCase()),
    body: actionBody(form, aggregate),
  })
  feedback.value = `${aggregate.record.businessNo} 已执行 ${action}`
  resetForm()
  await loadData(session, rows)
}

async function submitActivity(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
  form: LearningForm,
  aggregate: Phase10Aggregate,
  kind: LearningActivity,
  feedback: Ref<string>,
  resetForm: () => void,
): Promise<void> {
  await session.request(`${ENDPOINT}/${aggregate.record.id}/${kind}`, {
    method: 'POST', idempotencyKey: idempotencyKey('P010', kind),
    body: activityBody(form, kind),
  })
  feedback.value = `${aggregate.record.businessNo} 已提交学习证据`
  resetForm()
  await loadData(session, rows)
}

export function useLearningOperations() {
  const session = usePortalSessionStore()
  const rows = ref<Phase10Aggregate[]>([])
  const form = reactive(createLearningForm())
  const state = useAsyncActionState()
  const summary = useRecordSummary(rows)
  const orgId = computed(() => session.session?.orgId ?? '')
  const resetForm = () => Object.assign(form, createLearningForm())
  const load = () => state.run(() => loadData(session, rows))
  const create = () => state.run(
    () => submitAssignment(session, rows, form, orgId.value, state.feedback, resetForm),
  )
  const act = (aggregate: Phase10Aggregate, action: string) => state.run(
    () => executeAction(session, rows, form, aggregate, action, state.feedback, resetForm),
  )
  const activity = (aggregate: Phase10Aggregate, kind: LearningActivity) => state.run(
    () => submitActivity(session, rows, form, aggregate, kind, state.feedback, resetForm),
  )
  onMounted(() => void load())
  return {
    rows, form, resetForm, ...state, ...summary, load, create, act, activity,
    canRead: computed(() => session.can('p010.learning.read')),
    canComplete: computed(() => session.can('p010.learning.complete')),
    canExam: computed(() => session.can('p010.learning.exam')),
    canManage: computed(() => session.can('p010.learning.manage')),
    canCertify: computed(() => session.can('p010.learning.certify')),
    actionsFor: (aggregate: Phase10Aggregate) => LEARNING_ACTIONS[aggregate.record.currentNodeCode ?? ''] ?? [],
    canAct: (action: string) => CERTIFY_ACTIONS.has(action)
      ? session.can('p010.learning.certify')
      : session.can('p010.learning.manage'),
  }
}
