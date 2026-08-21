import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  useAsyncActionState,
  type AsyncActionState,
  type PortalSessionStore,
} from '../../phase10/shared'
import type { Phase11Action, Phase11Portal, Phase11Record } from '../types'

const ENDPOINT = '/api/v1/processes/P011/performance-cycles'

export const P011_ACTIONS: Readonly<Record<string, readonly Phase11Action[]>> = {
  S02: [{ code: 'CONFIRM_TARGETS', label: '确认绩效目标', permission: 'p011.performance.self' }],
  S03: [{ code: 'RECORD_COACHING', label: '登记过程辅导', permission: 'p011.performance.evaluate', needsSummary: true }],
  S04: [{ code: 'COLLECT_FACTS', label: '完成权威事实归集', permission: 'p011.performance.evaluate', needsSummary: true }],
  S05: [{ code: 'SUBMIT_REVIEWS', label: '确认评价事实齐备', permission: 'p011.performance.evaluate' }],
  S06: [{ code: 'CALCULATE_SCORE', label: '计算综合分', permission: 'p011.performance.evaluate' }],
  S07: [{ code: 'CALIBRATE', label: '确认校准分', permission: 'p011.performance.calibrate' }],
  S08: [{ code: 'SUBMIT_APPEAL_DECISION', label: '确认结果/提交申诉', permission: 'p011.performance.self' }],
  S09: [{ code: 'RESOLVE_APPEAL', label: '完成独立申诉复核', permission: 'p011.performance.appeal', needsSummary: true }],
  S10: [{ code: 'EXECUTE_IMPACT', label: '执行绩效影响', permission: 'p011.performance.impact', needsSummary: true }],
  S11: [{ code: 'ARCHIVE', label: '归档关闭', permission: 'p011.performance.impact', needsSummary: true }],
}

interface CreateForm {
  subject: string
  reason: string
  ownerEmployeeId: string
  periodNo: string
  goalSummary: string
  contentVersion: string
  factOccurredAt: string
}
interface ActionForm {
  summary: string
  reason: string
  appealRequested: boolean
  appealReason: string
  decision: string
}
interface ScoreForm { scoreType: string; score1000: string; evidenceSummary: string }
interface PerformanceContext {
  session: PortalSessionStore
  rows: Ref<Phase11Record[]>
  createForm: CreateForm
  actionForm: ActionForm
  scoreForm: ScoreForm
  state: AsyncActionState
  portal: Phase11Portal
}

function newCreateForm(): CreateForm {
  return {
    subject: '', reason: '', ownerEmployeeId: '', periodNo: '', goalSummary: '',
    contentVersion: 'P011-CONTENT-V1', factOccurredAt: '',
  }
}
function newActionForm(): ActionForm {
  return { summary: '', reason: '', appealRequested: false, appealReason: '', decision: '' }
}
function newScoreForm(portal: Phase11Portal): ScoreForm {
  return { scoreType: portal === 'employee' ? 'EMPLOYEE' : 'SUPERVISOR', score1000: '', evidenceSummary: '' }
}
function loadData(context: PerformanceContext): Promise<void> {
  return context.session.request<Phase11Record[]>(ENDPOINT)
    .then((records) => { context.rows.value = records })
}

function createPerformance(context: PerformanceContext): () => Promise<void> {
  return () => context.state.run(async () => {
    const session = context.session.session
    if (!session?.orgId) throw new Error('缺少中心身份')
    const owner = context.portal === 'employee'
      ? session.employeeId
      : context.createForm.ownerEmployeeId.trim()
    if (!owner) throw new Error('请选择绩效员工')
    await context.session.request(ENDPOINT, {
      method: 'POST', idempotencyKey: idempotencyKey('P011', 'create'),
      body: createBody(context.createForm, session.orgId, owner),
    })
    context.state.feedback.value = '绩效周期已创建并进入员工目标确认'
    Object.assign(context.createForm, newCreateForm())
    await loadData(context)
  })
}

function createBody(form: CreateForm, orgId: string, ownerEmployeeId: string) {
  return {
    subject: form.subject.trim(), reason: form.reason.trim(), priority: 'NORMAL',
    riskLevel: 'NORMAL', ownerCenterId: orgId, ownerEmployeeId,
    businessDate: new Date().toISOString().slice(0, 10),
    factOccurredAt: new Date(form.factOccurredAt).toISOString(),
    goalSummary: form.goalSummary.trim(), contentVersion: form.contentVersion.trim(),
    periodNo: form.periodNo.trim(),
  }
}

function submitPerformanceScore(context: PerformanceContext) {
  return (record: Phase11Record) => context.state.run(async () => {
    const score = Number(context.scoreForm.score1000)
    if (!Number.isInteger(score) || score < 0 || score > 1000) {
      throw new Error('分数必须为 0–1000 整数')
    }
    const type = context.scoreForm.scoreType
    await context.session.request(`${ENDPOINT}/${record.id}/scores/${type}`, {
      method: 'POST', idempotencyKey: idempotencyKey('P011', `score-${type.toLowerCase()}`),
      body: { expectedVersion: record.versionNo, score1000: score,
        evidenceSummary: context.scoreForm.evidenceSummary.trim() },
    })
    context.state.feedback.value = `${record.businessNo} 已登记 ${type} 独立分数事实`
    Object.assign(context.scoreForm, newScoreForm(context.portal))
    await loadData(context)
  })
}

function createPerformanceAction(context: PerformanceContext) {
  return (record: Phase11Record, action: Phase11Action) => context.state.run(async () => {
    await context.session.request(`${ENDPOINT}/${record.id}/actions/${action.code}`, {
      method: 'POST', idempotencyKey: idempotencyKey('P011', action.code.toLowerCase()),
      body: actionBody(context.actionForm, record, action.code),
    })
    context.state.feedback.value = `${record.businessNo} 已执行 ${action.label}`
    Object.assign(context.actionForm, newActionForm())
    await loadData(context)
  })
}

function actionBody(form: ActionForm, record: Phase11Record, action: string) {
  return {
    expectedVersion: record.versionNo, summary: form.summary.trim() || null,
    reason: form.reason.trim() || null,
    appealRequested: action === 'SUBMIT_APPEAL_DECISION' ? form.appealRequested : null,
    appealReason: form.appealReason.trim() || null, decision: form.decision.trim() || null,
  }
}

export function usePerformanceOperations(portal: Phase11Portal) {
  const context: PerformanceContext = {
    session: usePortalSessionStore(), rows: ref<Phase11Record[]>([]),
    createForm: reactive(newCreateForm()), actionForm: reactive(newActionForm()),
    scoreForm: reactive(newScoreForm(portal)), state: useAsyncActionState(), portal,
  }
  const load = () => context.state.run(() => loadData(context))
  const open = computed(() => context.rows.value.filter((row) => row.currentNodeCode !== 'END').length)
  onMounted(() => void load())
  return {
    rows: context.rows, createForm: context.createForm, actionForm: context.actionForm,
    scoreForm: context.scoreForm, total: computed(() => context.rows.value.length),
    open, closed: computed(() => context.rows.value.length - open.value), ...context.state,
    load, create: createPerformance(context), submitScore: submitPerformanceScore(context),
    act: createPerformanceAction(context), canAct: (action: Phase11Action) => context.session.can(action.permission),
    actionsFor: (record: Phase11Record) => P011_ACTIONS[record.currentNodeCode] ?? [],
    canCreate: computed(() => context.session.can('p011.performance.create')),
  }
}
