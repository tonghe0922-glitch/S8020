import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  toIso,
  useAsyncActionState,
  useRecordSummary,
  type Phase10Action,
  type Phase10Aggregate,
  type PortalSessionStore,
} from '../shared'

export interface OvertimeForm {
  subject: string
  reason: string
  attendanceType: string
  startAt: string
  endAt: string
  emergencyFact: boolean
  actualStartAt: string
  actualEndAt: string
  attendanceSummary: string
  resultSummary: string
  compensationPlan: string
  wageAmount: string
  quotaAccountId: string
  timeOffHours: string
  payrollReference: string
}

interface OvertimeActionBody {
  expectedVersion: number
  reason?: string | null
  actualStartAt?: string
  actualEndAt?: string
  attendanceSummary?: string
  resultSummary?: string
  compensationPlan?: string
  wageAmount?: number
  quotaAccountId?: string
  timeOffHours?: number
  payrollReference?: string
}

const ENDPOINT = '/api/v1/processes/P009/overtime-requests'
const REVIEW_ACTIONS = new Set(['APPROVE_OVERTIME', 'REJECT_OVERTIME'])
const HR_ACTIONS = new Set(['HR_REVIEW', 'SET_COMPENSATION_PLAN', 'ACK_PAYROLL_RECEIPT', 'ARCHIVE'])

export const OVERTIME_ACTIONS: Readonly<Record<string, readonly Phase10Action[]>> = {
  S02: [{ code: 'VALIDATE_NECESSITY', label: '完成必要性校验' }],
  S03: [
    { code: 'APPROVE_OVERTIME', label: '批准加班' },
    { code: 'REJECT_OVERTIME', label: '驳回申请', tone: 'danger' },
  ],
  S04: [{ code: 'RECORD_ACTUAL_FACT', label: '登记实际劳动' }],
  S05: [{ code: 'ACCEPT_RESULT', label: '验收成果' }],
  S06: [{ code: 'HR_REVIEW', label: '完成人事复核' }],
  S07: [{ code: 'SET_COMPENSATION_PLAN', label: '确定工资/调休方案' }],
  S08: [{ code: 'ACK_PAYROLL_RECEIPT', label: '确认薪酬回执' }],
  S09: [{ code: 'ARCHIVE', label: '归档' }],
}

function createOvertimeForm(): OvertimeForm {
  return {
    subject: '', reason: '', attendanceType: 'OVERTIME', startAt: '', endAt: '',
    emergencyFact: false, actualStartAt: '', actualEndAt: '', attendanceSummary: '',
    resultSummary: '', compensationPlan: 'WAGE', wageAmount: '0', quotaAccountId: '',
    timeOffHours: '', payrollReference: '',
  }
}

function createBody(form: OvertimeForm, ownerCenterId: string) {
  if (!ownerCenterId) throw new Error('缺少中心身份')
  return {
    subject: form.subject.trim(), reason: form.reason.trim() || null, ownerCenterId,
    attendanceType: form.attendanceType.trim(), startAt: toIso(form.startAt),
    endAt: toIso(form.endAt), emergencyFact: form.emergencyFact,
  }
}

function actualFields(form: OvertimeForm): Partial<OvertimeActionBody> {
  return {
    actualStartAt: form.actualStartAt ? toIso(form.actualStartAt) : undefined,
    actualEndAt: form.actualEndAt ? toIso(form.actualEndAt) : undefined,
    attendanceSummary: form.attendanceSummary.trim() || undefined,
    resultSummary: form.resultSummary.trim() || undefined,
  }
}

function compensationFields(form: OvertimeForm): Partial<OvertimeActionBody> {
  return {
    compensationPlan: form.compensationPlan,
    wageAmount: Number(form.wageAmount || 0),
    quotaAccountId: form.quotaAccountId.trim() || undefined,
    timeOffHours: form.timeOffHours ? Number(form.timeOffHours) : undefined,
    payrollReference: form.payrollReference.trim() || undefined,
  }
}

function actionBody(form: OvertimeForm, aggregate: Phase10Aggregate): OvertimeActionBody {
  return {
    expectedVersion: aggregate.record.versionNo,
    reason: form.reason.trim() || null,
    ...actualFields(form),
    ...compensationFields(form),
  }
}

async function loadData(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
): Promise<void> {
  rows.value = await session.request<Phase10Aggregate[]>(ENDPOINT)
}

function permissionFor(session: PortalSessionStore, action: string): boolean {
  if (REVIEW_ACTIONS.has(action)) return session.can('p009.overtime.review')
  if (HR_ACTIONS.has(action)) return session.can('p009.overtime.hr')
  if (action === 'RECORD_ACTUAL_FACT') return session.can('p009.overtime.submit')
  return session.can('p009.overtime.manage')
}

export function useOvertimeOperations() {
  const session = usePortalSessionStore()
  const rows = ref<Phase10Aggregate[]>([])
  const form = reactive(createOvertimeForm())
  const state = useAsyncActionState()
  const summary = useRecordSummary(rows)
  const orgId = computed(() => session.session?.orgId ?? '')
  const resetForm = () => Object.assign(form, createOvertimeForm())
  const load = () => state.run(() => loadData(session, rows))
  const create = () => state.run(async () => {
    await session.request(ENDPOINT, {
      method: 'POST', idempotencyKey: idempotencyKey('P009', 'create'),
      body: createBody(form, orgId.value),
    })
    state.feedback.value = '加班申请已提交并进入必要性校验'
    resetForm()
    await loadData(session, rows)
  })
  const act = (aggregate: Phase10Aggregate, action: string) => state.run(async () => {
    await session.request(`${ENDPOINT}/${aggregate.record.id}/actions/${action}`, {
      method: 'POST', idempotencyKey: idempotencyKey('P009', action.toLowerCase()),
      body: actionBody(form, aggregate),
    })
    state.feedback.value = `${aggregate.record.businessNo} 已执行 ${action}`
    resetForm()
    await loadData(session, rows)
  })
  onMounted(() => void load())
  return {
    rows, form, resetForm, ...state, ...summary, load, create, act,
    canSubmit: computed(() => session.can('p009.overtime.submit')),
    canReview: computed(() => session.can('p009.overtime.review')),
    canHr: computed(() => session.can('p009.overtime.hr')),
    canManage: computed(() => session.can('p009.overtime.manage')),
    actionsFor: (aggregate: Phase10Aggregate) => OVERTIME_ACTIONS[aggregate.record.currentNodeCode ?? ''] ?? [],
    canAct: (action: string) => permissionFor(session, action),
  }
}
