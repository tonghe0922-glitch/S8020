export interface ShiftRecord {
  id: string
  businessNo: string
  currentNodeCode: string | null
  status: string
  versionNo: number
  subject: string | null
  targetEmployeeId: string | null
  replacementEmployeeId: string | null
  templateCode: string | null
  periodOrCourseNo: string | null
  startAt: string
  endAt: string
  durationHours: number
  qualificationCheckedAt: string | null
  conflictCheckedAt: string | null
  approvedAt: string | null
  attendanceLinkedAt: string | null
  cateringLinkedAt: string | null
  shuttleLinkedAt: string | null
}

export interface ShiftAggregate {
  record: ShiftRecord
}

export interface ShiftForm {
  subject: string
  changeReason: string
  templateCode: string
  period: string
  targetEmployee: string
  startAt: string
  endAt: string
  replacement: string
  reason: string
}
