import {
  computed,
  ref,
  toValue,
  watch,
  type MaybeRefOrGetter,
} from 'vue'
import { usePortalSessionStore } from '../../session'
import {
  displayTime,
  useAsyncActionState,
  type Phase10Aggregate,
  type Phase10Record,
  type PortalSessionStore,
} from './shared'

export type Phase10Process = 'P006' | 'P007' | 'P008' | 'P009' | 'P010'

export interface Phase10MonitorRow {
  process: Phase10Process
  businessNo: string
  node: string
  status: string
  ownerCenterId: string
  ownerEmployeeId: string
  period: string
  integrationFact: string
  updatedAt: string
}

interface MeetingMonitorEnvelope {
  meeting: Phase10Record
}

const ENDPOINTS: Readonly<Record<Phase10Process, string>> = {
  P006: '/api/v1/processes/P006/meetings',
  P007: '/api/v1/processes/P007/schedules',
  P008: '/api/v1/processes/P008/leaves',
  P009: '/api/v1/processes/P009/overtime-requests',
  P010: '/api/v1/processes/P010/assignments',
}

function period(record: Phase10Record): string {
  const start = record.actualStartAt ?? record.startAt
  const end = record.actualEndAt ?? record.endAt
  if (!start && !end) return '-'
  return `${displayTime(start)} — ${displayTime(end)}`
}

const INTEGRATION_FACTS: Readonly<Record<
  Phase10Process,
  (record: Phase10Record) => string
>> = {
  P006: (record) => record.officialType ?? record.attendanceType ?? '会议行动项运行事实',
  P007: (record) => record.changeAction ?? record.periodOrCourseNo ?? '排班联动运行事实',
  P008: (record) => record.attendanceType ?? '请假/考勤联动待服务端投影',
  P009: (record) => record.payrollReference ?? record.compensationPlan ?? '薪酬联动待服务端投影',
  P010: (record) => record.permissionLinkedAt
    ? `权限已联动：${displayTime(record.permissionLinkedAt)}`
    : '资格权限尚未联动',
}

function integrationFact(process: Phase10Process, record: Phase10Record): string {
  return INTEGRATION_FACTS[process](record)
}

function recordOf(
  process: Phase10Process,
  aggregate: Phase10Aggregate | MeetingMonitorEnvelope,
): Phase10Record {
  return process === 'P006'
    ? (aggregate as MeetingMonitorEnvelope).meeting
    : (aggregate as Phase10Aggregate).record
}

function toMonitorRow(
  process: Phase10Process,
  aggregate: Phase10Aggregate | MeetingMonitorEnvelope,
): Phase10MonitorRow {
  const record = recordOf(process, aggregate)
  return {
    process,
    businessNo: record.businessNo,
    node: record.currentNodeCode ?? '-',
    status: record.status,
    ownerCenterId: record.ownerCenterId ?? '-',
    ownerEmployeeId: record.ownerEmployeeId ?? record.targetEmployeeId ?? '-',
    period: period(record),
    integrationFact: integrationFact(process, record),
    updatedAt: displayTime(record.updatedAt),
  }
}

async function fetchProcess(
  process: Phase10Process,
  session: PortalSessionStore,
): Promise<Phase10MonitorRow[]> {
  const aggregates = await session.request<Array<Phase10Aggregate | MeetingMonitorEnvelope>>(
    ENDPOINTS[process],
  )
  return aggregates.map((aggregate) => toMonitorRow(process, aggregate))
}

export function usePhase10TechMonitor(
  processes: MaybeRefOrGetter<readonly Phase10Process[]>,
) {
  const session = usePortalSessionStore()
  const rows = ref<Phase10MonitorRow[]>([])
  const state = useAsyncActionState()
  const load = () => state.run(async () => {
    const activeProcesses = toValue(processes)
    rows.value = []
    const groups = await Promise.all(activeProcesses.map(
      (process) => fetchProcess(process, session),
    ))
    rows.value = groups.flat()
    state.feedback.value = `已刷新 ${rows.value.length} 条运行投影`
  })
  const total = computed(() => rows.value.length)
  const open = computed(() => rows.value.filter(({ node }) => node !== 'END').length)
  const closed = computed(() => total.value - open.value)
  watch(
    () => toValue(processes),
    () => void load(),
    { immediate: true },
  )
  return { rows, ...state, load, total, open, closed }
}
