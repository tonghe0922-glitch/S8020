import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  useAsyncActionState,
  type AsyncActionState,
  type PortalSessionStore,
} from '../../phase10/shared'
import type { Phase11Action, Phase11Portal, Phase11Record } from '../types'

const ENDPOINT = '/api/v1/processes/P012/promotions'

export const P012_ACTIONS: Readonly<Record<string, readonly Phase11Action[]>> = {
  S02: [{ code: 'PASS_ELIGIBILITY', label: '通过资格校验', permission: 'p012.promotion.review', needsSummary: true }],
  S03: [{ code: 'SUBMIT_ASSESSMENT', label: '提交评审评价', permission: 'p012.promotion.review', needsSummary: true }],
  S04: [{ code: 'VERIFY_POSITION_BUDGET', label: '确认岗位与预算', permission: 'p012.promotion.review', needsSummary: true }],
  S05: [{ code: 'COMPLETE_REVIEW', label: '完成评审会', permission: 'p012.promotion.review', needsSummary: true }],
  S06: [{ code: 'APPROVE_PROMOTION', label: '批准晋升任命', permission: 'p012.promotion.appoint', needsSummary: true }],
  S07: [{ code: 'COMPLETE_NOTICE', label: '完成公示与沟通', permission: 'p012.promotion.appoint', needsSummary: true }],
  S08: [{ code: 'CONFIRM_APPOINTMENT', label: '确认任职安排', permission: 'p012.promotion.read' }],
  S09: [{ code: 'COMPLETE_VALIDATION', label: '完成任前校验', permission: 'p012.promotion.appoint', needsSummary: true }],
  S10: [{ code: 'ACTIVATE_APPOINTMENT', label: '正式生效任职', permission: 'p012.promotion.activate', needsSummary: true }],
}

interface CreateForm {
  subject: string
  reason: string
  ownerEmployeeId: string
  sourcePerformanceCycleId: string
  currentPositionId: string
  targetPositionId: string
  nominationSummary: string
  periodNo: string
  contentVersion: string
  factOccurredAt: string
  appointmentEffectiveDate: string
  promotionThresholdScore: string
  ceoMode: boolean
}

interface ActionForm {
  summary: string
  reason: string
  decision: string
  appointmentEffectiveDate: string
}

interface PromotionContext {
  session: PortalSessionStore
  rows: Ref<Phase11Record[]>
  createForm: CreateForm
  actionForm: ActionForm
  state: AsyncActionState
  portal: Phase11Portal
}

function newCreateForm(): CreateForm {
  return {
    subject: '',
    reason: '',
    ownerEmployeeId: '',
    sourcePerformanceCycleId: '',
    currentPositionId: '',
    targetPositionId: '',
    nominationSummary: '',
    periodNo: '',
    contentVersion: 'P012-CONTENT-V1',
    factOccurredAt: '',
    appointmentEffectiveDate: '',
    promotionThresholdScore: '800',
    ceoMode: false,
  }
}

function newActionForm(): ActionForm {
  return { summary: '', reason: '', decision: '', appointmentEffectiveDate: '' }
}

function loadData(context: PromotionContext): Promise<void> {
  return context.session.request<Phase11Record[]>(ENDPOINT)
    .then((records) => { context.rows.value = records })
}

function createPromotion(context: PromotionContext): () => Promise<void> {
  return () => context.state.run(async () => {
    const session = context.session.session
    if (!session?.orgId) throw new Error('缺少中心身份')
    const owner = context.portal === 'employee'
      ? session.employeeId
      : context.createForm.ownerEmployeeId.trim()
    if (!owner) throw new Error('缺少候选员工')
    const threshold = Number(context.createForm.promotionThresholdScore)
    if (!Number.isInteger(threshold) || threshold < 0 || threshold > 1000) {
      throw new Error('晋升阈值必须是 0–1000 的整数')
    }
    await context.session.request(ENDPOINT, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P012', 'create'),
      body: createBody(context.createForm, session.orgId, owner, threshold),
    })
    context.state.feedback.value = '晋升提名已建立并进入服务端资格校验'
    Object.assign(context.createForm, newCreateForm())
    await loadData(context)
  })
}

function createBody(
  form: CreateForm,
  ownerCenterId: string,
  ownerEmployeeId: string,
  promotionThresholdScore: number,
) {
  return {
    subject: form.subject.trim(),
    reason: form.reason.trim(),
    priority: 'NORMAL',
    riskLevel: 'HIGH',
    ownerCenterId,
    ownerEmployeeId,
    businessDate: new Date().toISOString().slice(0, 10),
    factOccurredAt: new Date(form.factOccurredAt).toISOString(),
    nominationSummary: form.nominationSummary.trim(),
    contentVersion: form.contentVersion.trim(),
    periodNo: form.periodNo.trim(),
    employmentType: 'PROMOTION',
    sourcePerformanceCycleId: form.sourcePerformanceCycleId.trim(),
    currentPositionId: form.currentPositionId.trim() || null,
    targetPositionId: form.targetPositionId.trim(),
    promotionThresholdScore,
    appointmentEffectiveDate: form.appointmentEffectiveDate,
    ceoMode: form.ceoMode,
  }
}

function createPromotionAction(context: PromotionContext) {
  return (record: Phase11Record, action: Phase11Action) => context.state.run(async () => {
    await context.session.request(`${ENDPOINT}/${record.id}/actions/${action.code}`, {
      method: 'POST',
      idempotencyKey: idempotencyKey('P012', action.code.toLowerCase()),
      body: {
        expectedVersion: record.versionNo,
        summary: context.actionForm.summary.trim() || null,
        reason: context.actionForm.reason.trim() || null,
        decision: context.actionForm.decision.trim() || null,
        appointmentEffectiveDate: context.actionForm.appointmentEffectiveDate || null,
      },
    })
    context.state.feedback.value = `${record.businessNo} 已执行 ${action.label}`
    Object.assign(context.actionForm, newActionForm())
    await loadData(context)
  })
}

export function usePromotionOperations(portal: Phase11Portal) {
  const context: PromotionContext = {
    session: usePortalSessionStore(),
    rows: ref<Phase11Record[]>([]),
    createForm: reactive(newCreateForm()),
    actionForm: reactive(newActionForm()),
    state: useAsyncActionState(),
    portal,
  }
  const load = () => context.state.run(() => loadData(context))
  const open = computed(() => context.rows.value.filter((row) => row.currentNodeCode !== 'END').length)
  onMounted(() => void load())
  return {
    rows: context.rows,
    createForm: context.createForm,
    actionForm: context.actionForm,
    total: computed(() => context.rows.value.length),
    open,
    closed: computed(() => context.rows.value.length - open.value),
    ...context.state,
    load,
    create: createPromotion(context),
    act: createPromotionAction(context),
    canAct: (action: Phase11Action) => context.session.can(action.permission),
    actionsFor: (record: Phase11Record) => P012_ACTIONS[record.currentNodeCode] ?? [],
    canCreate: computed(() => context.session.can('p012.promotion.create')),
  }
}
