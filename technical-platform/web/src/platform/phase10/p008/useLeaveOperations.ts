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

export interface LeaveLedgerEntry {
  id: string
  leaveRequestId: string
  businessNo: string
  ownerCenterId: string
  ownerEmployeeId: string | null
  sequence: number
  entryType: string
  amount: number | null
  note: string | null
  createdAt: string
}

export interface LeaveForm {
  subject: string
  reason: string
  attendanceType: string
  startAt: string
  endAt: string
  quotaAccountId: string
  quotaAmount: string
  handoverAgentId: string
  knownImpact: string
  actualAt: string
  adjustmentAmount: string
}

interface LeaveActionBody {
  expectedVersion: number
  reason?: string | null
  actualAt?: string
  adjustmentAmount?: number
}

const ENDPOINT = '/api/v1/processes/P008/leaves'
const LEDGER_ENDPOINT = '/api/v1/processes/P008/quota-ledger'
const REVIEW_ACTIONS = new Set(['APPROVE_LEAVE', 'REJECT_LEAVE'])
const EMPLOYEE_ACTIONS = new Set(['CONFIRM_HANDOVER', 'START_LEAVE', 'RETURN_TO_WORK', 'CHANGE_LEAVE'])

export const LEAVE_ACTIONS: Readonly<Record<string, readonly Phase10Action[]>> = {
  S02: [{ code: 'RESERVE_QUOTA', label: '预占额度' }],
  S03: [{ code: 'CONFIRM_HANDOVER', label: '确认交接' }],
  S04: [
    { code: 'APPROVE_LEAVE', label: '批准请假' },
    { code: 'REJECT_LEAVE', label: '驳回请假', tone: 'danger' },
  ],
  S05: [
    { code: 'COMMIT_QUOTA', label: '确认扣减' },
    { code: 'RELEASE_QUOTA', label: '释放预占', tone: 'secondary' },
  ],
  S06: [{ code: 'MARK_ATTENDANCE', label: '写入考勤标记' }],
  S07: [{ code: 'START_LEAVE', label: '确认开始休假' }],
  S08: [
    { code: 'RETURN_TO_WORK', label: '销假返岗' },
    { code: 'CHANGE_LEAVE', label: '变更休假', tone: 'secondary' },
  ],
  S09: [{ code: 'ADJUST_QUOTA', label: '登记差额调整' }],
  S10: [{ code: 'CLOSE_DAY', label: '日结归档' }],
}

function createLeaveForm(): LeaveForm {
  return {
    subject: '', reason: '', attendanceType: 'ANNUAL_LEAVE', startAt: '', endAt: '',
    quotaAccountId: 'ANNUAL', quotaAmount: '1', handoverAgentId: '', knownImpact: '',
    actualAt: '', adjustmentAmount: '0',
  }
}

function createBody(form: LeaveForm, ownerCenterId: string) {
  if (!ownerCenterId) throw new Error('缺少中心身份')
  return {
    subject: form.subject.trim(), reason: form.reason.trim() || null, ownerCenterId,
    attendanceType: form.attendanceType.trim(), quotaAccountId: form.quotaAccountId.trim(),
    quotaAmount: Number(form.quotaAmount), startAt: toIso(form.startAt), endAt: toIso(form.endAt),
    handoverAgentId: form.handoverAgentId.trim() || null, knownImpact: form.knownImpact.trim() || null,
  }
}

function actionBody(form: LeaveForm, aggregate: Phase10Aggregate): LeaveActionBody {
  const body: LeaveActionBody = {
    expectedVersion: aggregate.record.versionNo,
    reason: form.reason.trim() || null,
  }
  if (form.actualAt) body.actualAt = toIso(form.actualAt)
  if (form.adjustmentAmount) body.adjustmentAmount = Number(form.adjustmentAmount)
  return body
}

async function loadData(
  session: PortalSessionStore,
  rows: Ref<Phase10Aggregate[]>,
  ledger: Ref<LeaveLedgerEntry[]>,
): Promise<void> {
  const [nextRows, nextLedger] = await Promise.all([
    session.request<Phase10Aggregate[]>(ENDPOINT),
    session.request<LeaveLedgerEntry[]>(LEDGER_ENDPOINT),
  ])
  rows.value = nextRows
  ledger.value = nextLedger
}

function permissionFor(session: PortalSessionStore, action: string): boolean {
  if (REVIEW_ACTIONS.has(action)) return session.can('p008.leave.review')
  if (EMPLOYEE_ACTIONS.has(action)) return session.can('p008.leave.submit')
  return session.can('p008.leave.manage')
}

export function useLeaveOperations() {
  const session = usePortalSessionStore()
  const rows = ref<Phase10Aggregate[]>([])
  const ledger = ref<LeaveLedgerEntry[]>([])
  const form = reactive(createLeaveForm())
  const state = useAsyncActionState()
  const summary = useRecordSummary(rows)
  const orgId = computed(() => session.session?.orgId ?? '')
  const resetForm = () => Object.assign(form, createLeaveForm())

  const load = () => state.run(() => loadData(session, rows, ledger))
  const create = () => state.run(async () => {
    await session.request(ENDPOINT, {
      method: 'POST', idempotencyKey: idempotencyKey('P008', 'create'),
      body: createBody(form, orgId.value),
    })
    state.feedback.value = '请假申请已提交并进入额度预占流程'
    resetForm()
    await loadData(session, rows, ledger)
  })
  const act = (aggregate: Phase10Aggregate, action: string) => state.run(async () => {
    await session.request(`${ENDPOINT}/${aggregate.record.id}/actions/${action}`, {
      method: 'POST', idempotencyKey: idempotencyKey('P008', action.toLowerCase()),
      body: actionBody(form, aggregate),
    })
    state.feedback.value = `${aggregate.record.businessNo} 已执行 ${action}`
    resetForm()
    await loadData(session, rows, ledger)
  })

  onMounted(() => void load())
  return {
    rows, ledger, form, resetForm, ...state, ...summary, load, create, act,
    canSubmit: computed(() => session.can('p008.leave.submit')),
    canReview: computed(() => session.can('p008.leave.review')),
    canManage: computed(() => session.can('p008.leave.manage')),
    actionsFor: (aggregate: Phase10Aggregate) => LEAVE_ACTIONS[aggregate.record.currentNodeCode ?? ''] ?? [],
    canAct: (action: string) => permissionFor(session, action),
  }
}
