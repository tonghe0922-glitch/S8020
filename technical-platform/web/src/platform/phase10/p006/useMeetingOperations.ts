import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  optionalIso,
  useAsyncActionState,
  type PortalSessionStore,
} from '../shared'
import type { MeetingAggregate, MeetingForm, MeetingItem } from './types'

export interface MeetingAction {
  code: string
  label: string
}

export const MEETING_ACTIONS: Readonly<Record<string, readonly MeetingAction[]>> = {
  S02: [{ code: 'CONFIRM_MATERIALS', label: '确认材料完整' }],
  S03: [{ code: 'PUBLISH_MEETING', label: '发布会议' }],
  S05: [{ code: 'COMPLETE_MEETING', label: '结束会议' }],
  S06: [{ code: 'CONFIRM_MINUTES', label: '确认纪要' }],
  S07: [{ code: 'GENERATE_ACTION_ITEMS', label: '生成行动项' }],
  S09: [{ code: 'ACCEPT_ACTIONS', label: '验收全部行动项' }],
  S10: [{ code: 'RESOLVE_OVERDUE', label: '确认逾期升级事实' }],
  S11: [{ code: 'ARCHIVE', label: '归档复盘并关闭' }],
}

const ENDPOINT = '/api/v1/processes/P006/meetings'

function createForm(): MeetingForm {
  return {
    subject: '', content: '', venue: '', startAt: '', visibility: '内部',
    attendanceType: '现场', participants: '', agenda: '', minutes: '',
    actionTitle: '', actionOwner: '', actionDueAt: '', actionEvidence: '', reason: '',
  }
}

function parseIds(value: string): string[] {
  return [...new Set(value.split(/[\s,，;；]+/).map((item) => item.trim()).filter(Boolean))]
}

function createBody(form: MeetingForm, ownerCenterId: string) {
  const startAt = optionalIso(form.startAt)
  if (!ownerCenterId) throw new Error('当前会话缺少中心')
  if (!form.subject.trim() || !startAt) throw new Error('会议主题和召开时间必填')
  return {
    officialSubject: form.subject.trim(), officialType: '会议',
    officialContent: form.content.trim(), attendanceType: form.attendanceType,
    visibilityLevel: form.visibility, venueChannel: form.venue.trim() || null,
    ownerCenterId, businessDate: new Date().toISOString().slice(0, 10), startAt,
    participantEmployeeIds: parseIds(form.participants),
    agendaItems: form.agenda.split('\n').map((item) => item.trim()).filter(Boolean),
  }
}

function actionDraft(form: MeetingForm, actionCode: string) {
  if (actionCode !== 'GENERATE_ACTION_ITEMS') return null
  const dueAt = optionalIso(form.actionDueAt)
  if (!form.actionTitle.trim() || !form.actionOwner.trim() || !dueAt) {
    throw new Error('生成行动项需要标题、责任人和计划完成时间')
  }
  return [{
    title: form.actionTitle.trim(), ownerEmployeeId: form.actionOwner.trim(), dueAt,
  }]
}

function actionBody(form: MeetingForm, aggregate: MeetingAggregate, actionCode: string) {
  return {
    expectedVersion: aggregate.meeting.versionNo,
    minutesText: actionCode === 'CONFIRM_MINUTES' ? form.minutes.trim() : null,
    actionItems: actionDraft(form, actionCode), actionEvidence: null,
    actionItemIds: null, reason: form.reason.trim() || null,
  }
}

async function loadMeetings(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
): Promise<void> {
  const nextRows = await session.request<MeetingAggregate[]>(ENDPOINT)
  rows.splice(0, rows.length, ...nextRows)
}

async function submitMeeting(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
  form: MeetingForm,
  ownerCenterId: string,
  feedback: Ref<string>,
): Promise<void> {
  const result = await session.request<MeetingAggregate, ReturnType<typeof createBody>>(ENDPOINT, {
    method: 'POST', idempotencyKey: idempotencyKey('P006', 'create'),
    body: createBody(form, ownerCenterId),
  })
  feedback.value = `已创建 ${result.meeting.businessNo}，进入材料完整性检查`
  form.subject = ''
  form.content = ''
  await loadMeetings(session, rows)
}

async function executeMeetingAction(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
  form: MeetingForm,
  aggregate: MeetingAggregate,
  actionCode: string,
  feedback: Ref<string>,
): Promise<void> {
  await session.request(`${ENDPOINT}/${aggregate.meeting.id}/actions/${actionCode}`, {
    method: 'POST', idempotencyKey: idempotencyKey('P006', actionCode.toLowerCase()),
    body: actionBody(form, aggregate, actionCode),
  })
  feedback.value = `${aggregate.meeting.businessNo} 已执行 ${actionCode}`
  await loadMeetings(session, rows)
}

function simpleActionBody(aggregate: MeetingAggregate) {
  return {
    expectedVersion: aggregate.meeting.versionNo, minutesText: null,
    actionItems: null, actionEvidence: null, actionItemIds: null, reason: null,
  }
}

async function executeAttendance(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
  aggregate: MeetingAggregate,
  actionCode: string,
): Promise<void> {
  await session.request(`${ENDPOINT}/${aggregate.meeting.id}/actions/${actionCode}`, {
    method: 'POST', idempotencyKey: idempotencyKey('P006', actionCode.toLowerCase()),
    body: simpleActionBody(aggregate),
  })
  await loadMeetings(session, rows)
}

async function submitEvidence(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
  form: MeetingForm,
  aggregate: MeetingAggregate,
  item: MeetingItem,
): Promise<void> {
  if (!form.actionEvidence.trim()) throw new Error('执行证据不能为空')
  const body = {
    ...simpleActionBody(aggregate),
    actionEvidence: { [item.id]: form.actionEvidence.trim() },
  }
  await session.request(`${ENDPOINT}/${aggregate.meeting.id}/actions/SUBMIT_ACTION_EVIDENCE`, {
    method: 'POST', idempotencyKey: idempotencyKey('P006', 'evidence'), body,
  })
  form.actionEvidence = ''
  await loadMeetings(session, rows)
}

async function returnAction(
  session: PortalSessionStore,
  rows: MeetingAggregate[],
  form: MeetingForm,
  aggregate: MeetingAggregate,
  item: MeetingItem,
): Promise<void> {
  const body = {
    ...simpleActionBody(aggregate), actionItemIds: [item.id],
    reason: form.reason.trim() || '返工',
  }
  await session.request(`${ENDPOINT}/${aggregate.meeting.id}/actions/RETURN_ACTIONS`, {
    method: 'POST', idempotencyKey: idempotencyKey('P006', 'rework'), body,
  })
  await loadMeetings(session, rows)
}

export function useMeetingOperations(mode: 'employee' | 'center') {
  const session = usePortalSessionStore()
  const rows = ref<MeetingAggregate[]>([])
  const form = reactive(createForm())
  const state = useAsyncActionState()
  const orgId = computed(() => session.session?.orgId ?? '')
  const employeeId = computed(() => session.session?.employeeId ?? '')
  const load = () => state.run(() => loadMeetings(session, rows.value))
  const create = () => state.run(
    () => submitMeeting(session, rows.value, form, orgId.value, state.feedback),
  )
  const manage = (row: MeetingAggregate, code: string) => state.run(
    () => executeMeetingAction(session, rows.value, form, row, code, state.feedback),
  )
  const attendance = (row: MeetingAggregate, code: string) => state.run(
    () => executeAttendance(session, rows.value, row, code),
  )
  const evidence = (row: MeetingAggregate, item: MeetingItem) => state.run(
    () => submitEvidence(session, rows.value, form, row, item),
  )
  const rework = (row: MeetingAggregate, item: MeetingItem) => state.run(
    () => returnAction(session, rows.value, form, row, item),
  )
  onMounted(() => void load())
  return {
    rows, form, ...state, load, create, manage, attendance, evidence, rework,
    isCenter: computed(() => mode === 'center'), employeeId,
    canCreate: computed(() => session.can('p006.meeting.create')),
    canManage: computed(() => session.can('p006.meeting.manage')),
    canAccept: computed(() => session.can('p006.meeting.accept')),
    canAction: computed(() => session.can('p006.meeting.action')),
    total: computed(() => rows.value.length),
    open: computed(() => rows.value.filter(({ meeting }) => meeting.currentNodeCode !== 'END').length),
    closed: computed(() => rows.value.filter(({ meeting }) => meeting.currentNodeCode === 'END').length),
  }
}
