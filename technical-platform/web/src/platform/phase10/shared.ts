import { computed, ref, type Ref } from 'vue'
import type { StatusTone } from '../../design-system'
import type { usePortalSessionStore } from '../../session'

export type PortalSessionStore = ReturnType<typeof usePortalSessionStore>
export interface Phase10Record {
  id: string
  businessNo: string
  currentNodeCode: string | null
  status: string
  versionNo: number
  subject?: string | null
  reason?: string | null
  ownerCenterId?: string | null
  ownerEmployeeId?: string | null
  attendanceType?: string | null
  officialType?: string | null
  changeAction?: string | null
  targetEmployeeId?: string | null
  startAt?: string | null
  endAt?: string | null
  durationHours?: number | null
  emergencyFact?: boolean
  quotaAccountId?: string | null
  quotaAmount?: number | null
  decision?: string | null
  actualStartAt?: string | null
  actualEndAt?: string | null
  actualDurationHours?: number | null
  actualAttendanceSummary?: string | null
  resultSummary?: string | null
  supervisorDecision?: string | null
  compensationPlan?: string | null
  actualAmount?: number | null
  payrollReference?: string | null
  completionRate?: number | null
  score1000?: number | null
  practicalResult?: string | null
  courseVersionId?: string | null
  contentVersion?: string | null
  periodOrCourseNo?: string | null
  qualificationEffectiveDate?: string | null
  qualificationExpireDate?: string | null
  permissionLinkedAt?: string | null
  updatedAt?: string | null
}

export interface Phase10Evidence {
  id: string
  evidenceType: string
  score1000?: number | null
  completionRate?: number | null
  practicalResult?: string | null
  evidenceText?: string | null
  createdAt: string
}

export interface Phase10Aggregate {
  record: Phase10Record
  evidence?: readonly Phase10Evidence[]
}

export interface Phase10Action {
  code: string
  label: string
  tone?: 'primary' | 'secondary' | 'danger' | 'ghost'
}

export interface AsyncActionState {
  busy: Ref<boolean>
  feedback: Ref<string>
  failed: Ref<boolean>
  run: (task: () => Promise<void>) => Promise<void>
}

const STATUS_TONES: readonly [readonly string[], StatusTone][] = [
  [['CLOSE', 'ARCHIVE', 'COMPLETE', '通过', '完成', '归档'], 'success'],
  [['REJECT', 'FAIL', '驳回', '失败'], 'danger'],
  [['PENDING', 'WAIT', 'IN_PROGRESS', '待', '进行'], 'warning'],
]

export function useAsyncActionState(): AsyncActionState {
  const busy = ref(false)
  const feedback = ref('')
  const failed = ref(false)

  async function run(task: () => Promise<void>): Promise<void> {
    busy.value = true
    feedback.value = ''
    failed.value = false
    try {
      await task()
    } catch (error) {
      failed.value = true
      feedback.value = error instanceof Error ? error.message : '操作失败'
    } finally {
      busy.value = false
    }
  }

  return { busy, feedback, failed, run }
}

export function idempotencyKey(process: string, scope: string): string {
  return `${process}-${scope}-${globalThis.crypto.randomUUID()}`
}

export function toIso(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) throw new Error('日期时间无效')
  return parsed.toISOString()
}

export function optionalIso(value: string): string | null {
  return value ? toIso(value) : null
}

export function displayTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : '-'
}

export function displayNumber(value?: number | null, unit = ''): string {
  return value === null || value === undefined ? '-' : `${value}${unit}`
}

export function statusTone(status?: string | null): StatusTone {
  const normalized = status?.toUpperCase() ?? ''
  const match = STATUS_TONES.find(([terms]) => terms.some((term) => normalized.includes(term)))
  return match?.[1] ?? 'info'
}

export function useRecordSummary(rows: Ref<readonly Phase10Aggregate[]>) {
  const total = computed(() => rows.value.length)
  const open = computed(() => rows.value.filter(({ record }) => record.currentNodeCode !== 'END').length)
  const closed = computed(() => total.value - open.value)
  return { total, open, closed }
}
