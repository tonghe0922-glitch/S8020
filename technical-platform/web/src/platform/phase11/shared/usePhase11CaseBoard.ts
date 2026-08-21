import { computed, onMounted, reactive, ref, type Ref } from 'vue'
import { usePortalSessionStore } from '../../../session'
import {
  idempotencyKey,
  useAsyncActionState,
  type AsyncActionState,
  type PortalSessionStore,
} from '../../phase10/shared'
import type { Phase11Action, Phase11Portal, Phase11Record } from '../types'

export type Phase11CaseFieldKind = 'text' | 'number' | 'date' | 'datetime-local' | 'textarea'

export interface Phase11CaseField {
  key: string
  label: string
  kind: Phase11CaseFieldKind
  required?: boolean
  centerOnly?: boolean
  placeholder?: string
  min?: string
  max?: string
  step?: string
}

export interface Phase11CaseColumn {
  key: string
  label: string
  source: 'record' | 'details'
}

export interface Phase11CaseBoardConfig {
  processCode: string
  endpoint: string
  createPermission: string
  titles: Readonly<Record<Phase11Portal, string>>
  descriptions: Readonly<Record<Phase11Portal, string>>
  fields: readonly Phase11CaseField[]
  columns: readonly Phase11CaseColumn[]
  actions: Readonly<Record<string, readonly Phase11Action[]>>
  initialValues: Readonly<Record<string, string>>
  buildCreateBody: (
    values: Readonly<Record<string, string>>,
    ownerCenterId: string,
    ownerEmployeeId: string,
  ) => Readonly<Record<string, unknown>>
}

interface ActionForm {
  summary: string
  reason: string
  decision: string
  financeReferenceId: string
  receiptReference: string
}

interface BoardContext {
  session: PortalSessionStore
  rows: Ref<Phase11Record[]>
  createForm: Record<string, string>
  actionForm: ActionForm
  state: AsyncActionState
  portal: Phase11Portal
  config: Phase11CaseBoardConfig
}

function actionDefaults(): ActionForm {
  return {
    summary: '',
    reason: '',
    decision: '',
    financeReferenceId: '',
    receiptReference: '',
  }
}

function loadData(context: BoardContext): Promise<void> {
  return context.session.request<Phase11Record[]>(context.config.endpoint)
    .then((records) => { context.rows.value = records })
}

function createCase(context: BoardContext): () => Promise<void> {
  return () => context.state.run(async () => {
    const active = context.session.session
    if (!active?.orgId) throw new Error('缺少中心身份')
    const owner = context.portal === 'employee'
      ? active.employeeId
      : context.createForm.ownerEmployeeId?.trim()
    if (!owner) throw new Error('缺少奖励对象员工')
    const body = context.config.buildCreateBody(context.createForm, active.orgId, owner)
    await context.session.request(context.config.endpoint, {
      method: 'POST',
      idempotencyKey: idempotencyKey(context.config.processCode, 'create'),
      body,
    })
    context.state.feedback.value = `${context.config.processCode} 事项已创建并进入服务端工作流`
    Object.assign(context.createForm, context.config.initialValues)
    await loadData(context)
  })
}

function actOnCase(context: BoardContext) {
  return (record: Phase11Record, action: Phase11Action) => context.state.run(async () => {
    await context.session.request(`${context.config.endpoint}/${record.id}/actions/${action.code}`, {
      method: 'POST',
      idempotencyKey: idempotencyKey(context.config.processCode, action.code.toLowerCase()),
      body: {
        expectedVersion: record.versionNo,
        summary: context.actionForm.summary.trim() || null,
        reason: context.actionForm.reason.trim() || null,
        decision: context.actionForm.decision.trim() || null,
        financeReferenceId: context.actionForm.financeReferenceId.trim() || null,
        receiptReference: context.actionForm.receiptReference.trim() || null,
      },
    })
    context.state.feedback.value = `${record.businessNo} 已执行 ${action.label}`
    Object.assign(context.actionForm, actionDefaults())
    await loadData(context)
  })
}

export function usePhase11CaseBoard(
  portal: Phase11Portal,
  config: Phase11CaseBoardConfig,
) {
  const context: BoardContext = {
    session: usePortalSessionStore(),
    rows: ref<Phase11Record[]>([]),
    createForm: reactive({ ...config.initialValues }),
    actionForm: reactive(actionDefaults()),
    state: useAsyncActionState(),
    portal,
    config,
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
    create: createCase(context),
    act: actOnCase(context),
    canAct: (action: Phase11Action) => context.session.can(action.permission),
    actionsFor: (record: Phase11Record) => config.actions[record.currentNodeCode] ?? [],
    canCreate: computed(() => context.session.can(config.createPermission)),
  }
}
