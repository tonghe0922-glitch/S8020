export interface MeetingItem {
  id: string
  fieldCode: string
  itemName: string | null
  relatedObjectId: string | null
  actionOwnerEmployeeId: string | null
  actionDueAt: string | null
  actionStatus: string | null
  executionEvidence: string | null
  reworkCount: number
}

export interface MeetingRecord {
  id: string
  businessNo: string
  currentNodeCode: string | null
  status: string
  versionNo: number
  officialSubject: string | null
  officialContent: string | null
  startAt: string | null
}

export interface MeetingAggregate {
  meeting: MeetingRecord
  items: MeetingItem[]
}

export interface MeetingForm {
  subject: string
  content: string
  venue: string
  startAt: string
  visibility: string
  attendanceType: string
  participants: string
  agenda: string
  minutes: string
  actionTitle: string
  actionOwner: string
  actionDueAt: string
  actionEvidence: string
  reason: string
}
