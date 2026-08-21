import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  toIso,
  useAsyncActionState,
  type PortalSessionStore,
} from '../shared'
import type { ShiftAggregate, ShiftForm } from './types'

export interface ShiftAction {
  code: string
  label: string
}

export const SHIFT_ACTIONS: Readonly<Record<string, readonly ShiftAction[]>> = {
  S02: [{ code: 'MATCH_TEMPLATE', label: '确认班次模板' }],
  S03: [{ code: 'VALIDATE_SHIFT', label: '执行资格/连续工时/冲突校验' }],
  S04: [{ code: 'PUBLISH_SCHEDULE', label: '发布排班' }],
  S07: [
    { code: 'APPROVE_CHANGE', label: '批准变更' },
    { code: 'RETURN_CHANGE', label: '退回变更' },
  ],
  S08: [{ code: 'LINK_DEPENDENCIES', label: '写入考勤/餐饮/班车联动' }],
  S09: [{ code: 'CLOSE_DAY', label: '日结归档' }],
}

const LIST_ENDPOINT = '/api/v1/processes/P007/schedules'
const ACTION_ENDPOINT = '/api/v1/processes/P007/shift-changes'
const REVIEW_ACTIONS = new Set(['APPROVE_CHANGE', 'RETURN_CHANGE'])

function createForm(): ShiftForm {
  return {
    subject: '', changeReason: '', templateCode: '', period: '',
    targetEmployee: '', startAt: '', endAt: '', replacement: '', reason: '',
  }
}

function createBody(
  form: ShiftForm,
  center: boolean,
  ownerCenterId: string,
  employeeId: string,
) {
  const targetEmployeeId = center ? form.targetEmployee.trim() : employeeId
  if (!ownerCenterId || !targetEmployeeId) throw new Error('缺少员工/中心身份')
  if (!form.subject.trim() || !form.period.trim()) throw new Error('主题和周期编号必填')
  return {
    subject: form.subject.trim(), reason: null, ownerCenterId, targetEmployeeId,
    changeAction: center ? 'SCHEDULE' : 'SHIFT_CHANGE',
    changeReason: form.changeReason.trim(), templateCode: form.templateCode.trim() || null,
    periodOrCourseNo: form.period.trim(), startAt: toIso(form.startAt), endAt: toIso(form.endAt),
  }
}

function actionBody(form: ShiftForm, aggregate: ShiftAggregate, action: string) {
  return {
    expectedVersion: aggregate.record.versionNo,
    replacementEmployeeId: action === 'SUBMIT_SHIFT_CHANGE'
      ? form.replacement.trim() || null
      : null,
    reason: form.reason.trim() || null,
  }
}

async function loadShifts(
  session: PortalSessionStore,
  rows: ShiftAggregate[],
): Promise<void> {
  const nextRows = await session.request<ShiftAggregate[]>(LIST_ENDPOINT)
  rows.splice(0, rows.length, ...nextRows)
}

async function submitShift(
  session: PortalSessionStore,
  rows: ShiftAggregate[],
  form: ShiftForm,
  center: boolean,
  ownerCenterId: string,
  employeeId: string,
  feedback: Ref<string>,
): Promise<void> {
  const body = createBody(form, center, ownerCenterId, employeeId)
  await session.request(ACTION_ENDPOINT, {
    method: 'POST', idempotencyKey: idempotencyKey('P007', 'create'), body,
  })
  feedback.value = '排班/换班需求已进入班次模板匹配'
  await loadShifts(session, rows)
}

async function executeShiftAction(
  session: PortalSessionStore,
  rows: ShiftAggregate[],
  form: ShiftForm,
  aggregate: ShiftAggregate,
  action: string,
  feedback: Ref<string>,
): Promise<void> {
  const path = `${ACTION_ENDPOINT}/${aggregate.record.id}/actions/${action}`
  await session.request(path, {
    method: 'POST', idempotencyKey: idempotencyKey('P007', action.toLowerCase()),
    body: actionBody(form, aggregate, action),
  })
  feedback.value = `${aggregate.record.businessNo} 已执行 ${action}`
  await loadShifts(session, rows)
}

export function useShiftOperations(mode: 'employee' | 'center') {
  const session = usePortalSessionStore()
  const rows = ref<ShiftAggregate[]>([])
  const form = reactive(createForm())
  const state = useAsyncActionState()
  const center = computed(() => mode === 'center')
  const orgId = computed(() => session.session?.orgId ?? '')
  const employeeId = computed(() => session.session?.employeeId ?? '')
  const load = () => state.run(() => loadShifts(session, rows.value))
  const create = () => state.run(
    () => submitShift(
      session, rows.value, form, center.value, orgId.value, employeeId.value, state.feedback,
    ),
  )
  const act = (row: ShiftAggregate, action: string) => state.run(
    () => executeShiftAction(session, rows.value, form, row, action, state.feedback),
  )
  const canManage = computed(() => session.can('p007.schedule.manage'))
  const canReview = computed(() => session.can('p007.schedule.review'))
  onMounted(() => void load())
  return {
    rows, form, ...state, load, create, act, center, canManage, canReview,
    canChange: computed(() => session.can('p007.schedule.change')),
    canAct: (action: string) => REVIEW_ACTIONS.has(action) ? canReview.value : canManage.value,
    total: computed(() => rows.value.length),
    open: computed(() => rows.value.filter(({ record }) => record.currentNodeCode !== 'END').length),
    closed: computed(() => rows.value.filter(({ record }) => record.currentNodeCode === 'END').length),
  }
}
