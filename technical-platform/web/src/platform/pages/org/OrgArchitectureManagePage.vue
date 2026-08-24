<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiClientError } from '../../../api/api-error'
import type {
  OrgArchitectureDraft,
  OrgArchitectureNode,
  OrgArchitectureNodeCommand,
} from '../../../contracts'
import { createOrgArchitectureApi } from '../../../services/org-architecture/org-architecture-api'
import { usePortalSessionStore } from '../../../session'
import OrgArchitectureChart from './OrgArchitectureChart.vue'
import OrgNodeEditor from './OrgNodeEditor.vue'
import {
  architectureError,
  cloneNodes,
  commandFromNode,
  draftStatusLabel,
  emptyNodeCommand,
  newDraftNode,
  orgTypeLabel,
} from './org-architecture-view-model'

const session = usePortalSessionStore()
const api = createOrgArchitectureApi(session)
const draft = ref<OrgArchitectureDraft | null>(null)
const pending = ref<OrgArchitectureDraft[]>([])
const nodes = ref<OrgArchitectureNode[]>([])
const selectedId = ref('')
const editingId = ref('')
const editor = ref<OrgArchitectureNodeCommand>(emptyNodeCommand())
const title = ref(`组织架构调整 ${new Date().toLocaleDateString('zh-CN')}`)
const reviewComment = ref('')
const query = ref('')
const typeFilter = ref('')
const chartScale = ref(1)
const detailOpen = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const canEdit = computed(() => session.can('org.architecture.edit') || session.can('org.architecture.manage'))
const canReview = computed(() => session.can('org.architecture.review') || session.can('org.architecture.manage'))
const canPublish = computed(() => session.can('org.architecture.publish') || session.can('org.architecture.manage'))
const selected = computed(() => nodes.value.find((node) => node.id === selectedId.value) ?? null)
const editable = computed(() => canEdit.value && Boolean(draft.value)
  && (draft.value?.status === 'DRAFT' || draft.value?.status === 'REJECTED'))
const activeCount = computed(() => nodes.value.filter((node) => node.status === 'ACTIVE').length)
const centerCount = computed(() => nodes.value.filter((node) => node.orgType === 'CENTER').length)
const memberCount = computed(() => nodes.value.reduce((sum, node) => sum + node.memberCount, 0))
const typeOptions = computed(() => Array.from(new Set(nodes.value.map((node) => node.orgType))))
const selectedChildren = computed(() => selected.value
  ? nodes.value.filter((node) => node.parentId === selected.value?.id)
  : [])

const displayedNodes = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword && !typeFilter.value) return nodes.value
  const nodeMap = new Map(nodes.value.map((node) => [node.id, node]))
  const visibleIds = new Set<string>()
  for (const node of nodes.value) {
    const matchesKeyword = !keyword || [node.orgName, node.orgCode, orgTypeLabel(node.orgType)]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
    const matchesType = !typeFilter.value || node.orgType === typeFilter.value
    if (!matchesKeyword || !matchesType) continue
    visibleIds.add(node.id)
    let current = node
    while (current.parentId) {
      visibleIds.add(current.parentId)
      const parent = nodeMap.get(current.parentId)
      if (!parent) break
      current = parent
    }
  }
  return nodes.value.filter((node) => visibleIds.has(node.id))
})

const ancestorTrail = computed<OrgArchitectureNode[]>(() => {
  if (!selected.value) return []
  const trail: OrgArchitectureNode[] = []
  const visited = new Set<string>()
  let current: OrgArchitectureNode | undefined = selected.value
  while (current && !visited.has(current.id)) {
    trail.unshift(current)
    visited.add(current.id)
    current = current.parentId ? nodes.value.find((node) => node.id === current?.parentId) : undefined
  }
  return trail
})

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

function activate(value: OrgArchitectureDraft): void {
  draft.value = value
  title.value = value.title
  nodes.value = cloneNodes(value.snapshot)
  const first = nodes.value[0]
  if (first) selectNode(first.id, false)
  else startNew(null)
}

function selectNode(nodeId: string, openDrawer = true): void {
  const node = nodes.value.find((candidate) => candidate.id === nodeId)
  if (!node) return
  selectedId.value = node.id
  editingId.value = node.id
  editor.value = commandFromNode(node)
  detailOpen.value = openDrawer
}

function startNew(parentId: string | null): void {
  selectedId.value = ''
  editingId.value = ''
  editor.value = emptyNodeCommand(parentId)
  detailOpen.value = true
}

function closeDetail(): void {
  detailOpen.value = false
  const selectedNode = selected.value
  if (selectedNode) editor.value = commandFromNode(selectedNode)
}

async function loadPending(): Promise<void> {
  if (!canReview.value) return
  pending.value = await api.pendingDrafts()
}

async function restoreEditableDraft(): Promise<void> {
  if (!canEdit.value) return
  try {
    activate(await api.latestEditableDraft())
  } catch (cause) {
    if (!(cause instanceof ApiClientError) || cause.status !== 404) throw cause
  }
}

async function load(): Promise<void> {
  loading.value = true
  clearFeedback()
  try {
    await restoreEditableDraft()
    await loadPending()
    if (!draft.value && pending.value[0]) activate(pending.value[0])
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    loading.value = false
  }
}

async function createDraft(): Promise<void> {
  saving.value = true
  clearFeedback()
  try {
    const created = await api.createDraft(title.value.trim() || '组织架构调整')
    activate(created)
    detailOpen.value = false
    notice.value = '服务端草稿已创建，正式组织尚未改变。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

function applyEditor(): void {
  if (!editable.value) return
  if (editingId.value) {
    const index = nodes.value.findIndex((node) => node.id === editingId.value)
    if (index < 0) return
    const current = nodes.value[index]
    if (!current) return
    nodes.value[index] = {
      ...current,
      orgCode: editor.value.orgCode,
      orgName: editor.value.orgName,
      orgType: editor.value.orgType,
      parentId: editor.value.parentId,
      path: '',
      status: editor.value.status,
      managerEmployeeId: editor.value.managerEmployeeId,
      headcountPlan: editor.value.headcountPlan,
      sortNo: editor.value.sortNo,
      description: editor.value.description,
    }
    selectNode(editingId.value, false)
    detailOpen.value = false
    notice.value = '本地草稿已调整，请点击“保存草稿”写入服务端。'
    return
  }
  const created = newDraftNode(editor.value)
  nodes.value.push(created)
  selectNode(created.id, false)
  detailOpen.value = false
  notice.value = '新节点已加入本地草稿，请保存草稿。'
}

function removeSelected(): void {
  if (!selected.value || !editable.value) return
  if (!globalThis.confirm(`从草稿中移除“${selected.value.orgName}”及其下级节点？`)) return
  const removing = new Set<string>([selected.value.id])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes.value) {
      if (node.parentId && removing.has(node.parentId) && !removing.has(node.id)) {
        removing.add(node.id)
        changed = true
      }
    }
  }
  nodes.value = nodes.value.filter((node) => !removing.has(node.id))
  detailOpen.value = false
  const first = nodes.value[0]
  if (first) selectNode(first.id, false)
  else startNew(null)
  notice.value = '节点已从本地草稿移除；发布时服务端仍会校验在岗成员保护。'
}

async function saveDraft(): Promise<void> {
  if (!draft.value) return
  saving.value = true
  clearFeedback()
  try {
    const saved = await api.updateDraft(draft.value.id, title.value, draft.value.versionNo, nodes.value)
    activate(saved)
    detailOpen.value = false
    notice.value = `草稿已保存，共 ${saved.changes.length} 项变更。`
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function submitDraft(): Promise<void> {
  if (!draft.value) return
  saving.value = true
  clearFeedback()
  try {
    const submitted = await api.submitDraft(draft.value.id, draft.value.versionNo)
    activate(submitted)
    await loadPending()
    detailOpen.value = false
    notice.value = '变更清单已提交审批，正式组织尚未改变。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function review(approved: boolean): Promise<void> {
  if (!draft.value) return
  saving.value = true
  clearFeedback()
  try {
    const reviewed = await api.reviewDraft(
      draft.value.id,
      draft.value.versionNo,
      approved,
      reviewComment.value,
    )
    activate(reviewed)
    await loadPending()
    reviewComment.value = ''
    notice.value = approved ? '审批已通过，可以执行发布。' : '草稿已驳回并回到提交人。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function publishDraft(): Promise<void> {
  if (!draft.value) return
  if (!globalThis.confirm('发布后将以单事务写入正式组织，并立即同步到技术端和员工端组织架构。确认发布？')) return
  saving.value = true
  clearFeedback()
  try {
    const published = await api.publishDraft(draft.value.id, draft.value.versionNo)
    activate(published)
    await loadPending()
    detailOpen.value = false
    notice.value = '组织架构已发布，企业架构和通讯录已读取最新版本。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function abandonDraft(): Promise<void> {
  if (!draft.value || !globalThis.confirm('确认废弃当前草稿？')) return
  saving.value = true
  clearFeedback()
  try {
    activate(await api.abandonDraft(draft.value.id, draft.value.versionNo))
    detailOpen.value = false
    notice.value = '草稿已废弃。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

function openPending(value: OrgArchitectureDraft): void {
  activate(value)
  reviewComment.value = ''
  detailOpen.value = false
}

function zoom(delta: number): void {
  chartScale.value = Math.min(1.25, Math.max(.7, Number((chartScale.value + delta).toFixed(2))))
}

function initials(value: string): string {
  return value.trim().slice(-2)
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => void load())
</script>

<template>
  <section class="manage-page" :aria-busy="loading">
    <header class="manage-page__header">
      <div class="manage-page__heading">
        <p>中心事务 / 企业治理 / 公司架构管理</p>
        <div><h1>公司架构管理</h1><span>草稿调整、审批和发布在同一页面完成</span></div>
      </div>
      <div class="manage-page__header-actions">
        <span><i :class="{ 'is-loading': loading }" />{{ loading ? '正在同步' : '数据已同步' }}</span>
        <button type="button" :disabled="loading" @click="load">
          <svg viewBox="0 0 16 16"><path d="M13.5 5.2V2.5l-1.2 1.2A5.7 5.7 0 1 0 13 10M13.5 2.5h-2.7" /></svg>刷新
        </button>
      </div>
    </header>

    <p v-if="error" class="manage-page__feedback is-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="manage-page__feedback is-success" role="status">{{ notice }}</p>

    <section class="manage-page__overview">
      <div class="manage-page__overview-title">
        <span class="manage-page__overview-icon"><svg viewBox="0 0 24 24"><path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4" /></svg></span>
        <div>
          <small>ORGANIZATION GOVERNANCE</small>
          <strong>{{ draft?.title || '创建新的组织架构调整草稿' }}</strong>
          <span v-if="draft">基线 {{ draft.baseVersion }} · 草稿版本 {{ draft.versionNo }} · 更新于 {{ formatDate(draft.updatedAt) }}</span>
          <span v-else>所有修改先进入草稿，审批并发布后才影响正式组织。</span>
        </div>
      </div>
      <div v-if="draft" class="manage-page__status-card" :data-status="draft.status">
        <small>当前草稿状态</small>
        <strong>{{ draftStatusLabel(draft.status) }}</strong>
        <span>{{ draft.changes.length }} 项已识别变更</span>
      </div>
      <div v-else class="manage-page__status-card is-empty">
        <small>当前状态</small><strong>暂无草稿</strong><span>可创建新的架构调整</span>
      </div>
      <div class="manage-page__overview-metrics">
        <article><small>组织节点</small><strong>{{ nodes.length }}</strong><span>个</span></article>
        <article><small>启用组织</small><strong>{{ activeCount }}</strong><span>个</span></article>
        <article><small>中心数量</small><strong>{{ centerCount }}</strong><span>个</span></article>
        <article><small>在岗任职</small><strong>{{ memberCount }}</strong><span>人次</span></article>
        <article><small>变更清单</small><strong>{{ draft?.changes.length ?? 0 }}</strong><span>项</span></article>
      </div>
    </section>

    <section class="manage-page__command-bar">
      <label>
        <span>草稿标题</span>
        <input v-model="title" maxlength="160" :disabled="Boolean(draft && !editable)" placeholder="请输入本次组织架构调整标题">
      </label>
      <div>
        <button v-if="canEdit && !draft" type="button" class="is-primary" :disabled="saving" @click="createDraft">
          <svg viewBox="0 0 16 16"><path d="M8 2.5v11M2.5 8h11" /></svg>创建草稿
        </button>
        <button v-if="editable" type="button" :disabled="saving" @click="saveDraft">
          <svg viewBox="0 0 16 16"><path d="M2.3 2.3h9.5l1.9 1.9v9.5H2.3zM4.5 2.3v4h6v-4M5 10.5h6" /></svg>保存草稿
        </button>
        <button v-if="editable" type="button" class="is-primary" :disabled="saving || !draft?.changes.length" @click="submitDraft">
          <svg viewBox="0 0 16 16"><path d="m2.2 8 11.6-5-3.6 11-2.1-4zM8.1 10 13.8 3" /></svg>提交审批
        </button>
        <button v-if="editable" type="button" class="is-danger" :disabled="saving" @click="abandonDraft">废弃草稿</button>
        <button v-if="draft?.status === 'APPROVED' && canPublish" type="button" class="is-publish" :disabled="saving" @click="publishDraft">
          <svg viewBox="0 0 16 16"><path d="M8 13.5v-9M4.8 7.7 8 4.3l3.2 3.4M2.5 11.5v2h11v-2" /></svg>发布正式架构
        </button>
      </div>
    </section>

    <section class="manage-page__architecture-card">
      <header>
        <div><strong>草稿组织架构图</strong><span>{{ displayedNodes.length }} 个可见节点 · 点击卡片查看或编辑详情</span></div>
        <div class="manage-page__chart-tools">
          <label>
            <svg viewBox="0 0 16 16"><circle cx="7" cy="7" r="4.3" /><path d="m10.3 10.3 3.2 3.2" /></svg>
            <input v-model="query" type="search" placeholder="搜索组织名称或编码">
            <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''">×</button>
          </label>
          <select v-model="typeFilter" aria-label="组织类型筛选">
            <option value="">全部类型</option>
            <option v-for="type in typeOptions" :key="type" :value="type">{{ orgTypeLabel(type) }}</option>
          </select>
          <button type="button" title="缩小" :disabled="chartScale <= .7" @click="zoom(-.1)">−</button>
          <span>{{ Math.round(chartScale * 100) }}%</span>
          <button type="button" title="放大" :disabled="chartScale >= 1.25" @click="zoom(.1)">＋</button>
        </div>
      </header>
      <div class="manage-page__chart-stage">
        <div class="manage-page__chart-scale" :style="{ '--chart-scale': String(chartScale) }">
          <OrgArchitectureChart :nodes="displayedNodes" :selected-id="selectedId" @select="selectNode" />
        </div>
      </div>
      <footer>
        <div v-if="editable" class="manage-page__node-actions">
          <button type="button" @click="startNew(null)"><svg viewBox="0 0 16 16"><path d="M8 2.5v11M2.5 8h11" /></svg>新增顶级组织</button>
          <button type="button" :disabled="!selected" @click="startNew(selected?.id ?? null)">新增下级</button>
          <button type="button" class="is-danger" :disabled="!selected" @click="removeSelected">从草稿移除</button>
        </div>
        <span v-else>当前草稿不可编辑；审批、发布或历史状态只读展示。</span>
        <em>草稿修改不会直接影响正式组织。</em>
      </footer>
    </section>

    <div class="manage-page__lower-grid">
      <section class="manage-page__changes">
        <header><div><strong>变更清单</strong><span>保存草稿后由服务端生成正式差异</span></div><em>{{ draft?.changes.length ?? 0 }} 项</em></header>
        <div class="manage-page__change-list">
          <article v-for="change in draft?.changes ?? []" :key="`${change.kind}-${change.nodeId}`">
            <span :data-kind="change.kind">{{ change.kind }}</span><p>{{ change.summary }}</p><small>{{ change.orgCode || '待生成编码' }}</small>
          </article>
          <div v-if="!draft?.changes.length" class="manage-page__empty">
            <svg viewBox="0 0 24 24"><path d="M5 4h14v16H5zM8 8h8M8 12h8M8 16h5" /></svg><strong>暂无变更记录</strong><span>编辑节点并保存草稿后，将在此生成变更清单。</span>
          </div>
        </div>
      </section>

      <section v-if="canReview" class="manage-page__pending">
        <header><div><strong>待审批草稿</strong><span>选择草稿后可查看变更并执行审批</span></div><em>{{ pending.length }} 项</em></header>
        <div class="manage-page__pending-list">
          <button
            v-for="item in pending" :key="item.id" type="button"
            :class="{ 'is-active': item.id === draft?.id }"
            @click="openPending(item)"
          >
            <span>{{ initials(item.title) }}</span>
            <div><strong>{{ item.title }}</strong><small>{{ item.changes.length }} 项变更 · 草稿版本 {{ item.versionNo }}</small></div>
            <i>{{ draftStatusLabel(item.status) }}</i><b>›</b>
          </button>
          <div v-if="!pending.length" class="manage-page__empty is-small">
            <strong>当前没有待审批草稿</strong><span>新草稿提交后会自动出现在这里。</span>
          </div>
        </div>
      </section>
    </div>

    <section v-if="draft?.status === 'PENDING' && canReview" class="manage-page__review-bar">
      <div><strong>审批当前草稿</strong><span>请审阅组织层级、职责、编制及变更清单后给出意见。</span></div>
      <textarea v-model="reviewComment" rows="2" placeholder="填写审批意见（驳回时建议说明原因）" />
      <button type="button" class="is-reject" :disabled="saving" @click="review(false)">驳回</button>
      <button type="button" class="is-approve" :disabled="saving" @click="review(true)">审批通过</button>
    </section>

    <div v-if="detailOpen" class="manage-page__drawer-backdrop" @click.self="closeDetail">
      <aside class="manage-page__drawer" role="dialog" aria-modal="true" aria-label="组织草稿详情">
        <header>
          <div class="manage-page__drawer-crumbs">
            <button type="button" @click="detailOpen = false">草稿组织架构</button>
            <template v-for="item in ancestorTrail" :key="item.id"><span>/</span><button type="button" @click="selectNode(item.id)">{{ item.orgName }}</button></template>
            <template v-if="!editingId"><span>/</span><b>新增组织</b></template>
          </div>
          <button type="button" class="manage-page__drawer-close" aria-label="关闭详情" @click="closeDetail">×</button>
        </header>

        <div class="manage-page__drawer-identity">
          <span :data-type="selected?.orgType ?? editor.orgType">{{ initials(selected?.orgName || '新组织') }}</span>
          <div><p>{{ orgTypeLabel(selected?.orgType ?? editor.orgType) }}<i :class="{ 'is-on': (selected?.status ?? editor.status) === 'ACTIVE' }">{{ (selected?.status ?? editor.status) === 'ACTIVE' ? '启用' : '停用' }}</i></p><h2>{{ selected?.orgName || '新增草稿组织' }}</h2><small>{{ selected?.orgCode || '保存并发布后自动生成组织编码' }}</small></div>
          <em v-if="selected">{{ selectedChildren.length }} 个下级</em>
        </div>

        <OrgNodeEditor
          v-model="editor"
          :nodes="nodes"
          :editing-id="editingId"
          :disabled="!editable || saving"
          :title="editingId ? '调整草稿节点' : '新增草稿节点'"
          submit-label="应用到本地草稿"
          embedded
          @submit="applyEditor"
          @cancel="closeDetail"
        />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.manage-page {
  --manage-orange: #df6830;
  --manage-border: #e4e7ed;
  display: grid;
  gap: 1rem;
  min-width: 0;
  padding: 1.15rem clamp(1rem, 2vw, 1.55rem) 1.55rem;
  background: #f5f6f8;
  color: #273248;
}
.manage-page__header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.manage-page__heading { display: grid; gap: .28rem; }
.manage-page__heading > p { margin: 0; color: #9aa3b2; font-size: .64rem; }
.manage-page__heading > div { display: flex; align-items: baseline; gap: .7rem; }
.manage-page__heading h1 { margin: 0; font-size: 1.38rem; letter-spacing: -.02em; }
.manage-page__heading span { color: #8d97a7; font-size: .72rem; }
.manage-page__header-actions { display: flex; align-items: center; gap: .65rem; color: #8b95a5; font-size: .66rem; }
.manage-page__header-actions > span { display: flex; align-items: center; gap: .35rem; }
.manage-page__header-actions i { width: .42rem; height: .42rem; border-radius: 50%; background: #4fa369; box-shadow: 0 0 0 3px #e8f7ed; }
.manage-page__header-actions i.is-loading { background: #e99343; box-shadow: 0 0 0 3px #fff1df; animation: manage-pulse 1s infinite; }
.manage-page button,
.manage-page input,
.manage-page select,
.manage-page textarea { font: inherit; }
.manage-page__header-actions button { display: inline-flex; align-items: center; gap: .35rem; min-height: 2.3rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: .52rem .72rem; background: #fff; color: #647086; font-size: .68rem; font-weight: 720; cursor: pointer; }
.manage-page__header-actions svg { width: .82rem; height: .82rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.manage-page button:disabled { cursor: not-allowed; opacity: .48; }
.manage-page__feedback { margin: 0; padding: .72rem .9rem; border: 1px solid; border-radius: .7rem; font-size: .73rem; }
.manage-page__feedback.is-error { border-color: #f0c6bf; background: #fff1ef; color: #a53b2e; }
.manage-page__feedback.is-success { border-color: #bfe2cb; background: #eff9f2; color: #287047; }
.manage-page__overview { display: grid; grid-template-columns: minmax(18rem, 1.2fr) 10rem minmax(30rem, 2fr); align-items: stretch; gap: .75rem; }
.manage-page__overview-title { display: flex; align-items: center; gap: .8rem; padding: .9rem; border: 1px solid #eadfd9; border-radius: .88rem; background: linear-gradient(135deg, #fff8f3, #fff); box-shadow: 0 5px 18px rgb(57 43 34 / 4%); }
.manage-page__overview-icon { display: grid; flex: 0 0 auto; place-items: center; width: 3rem; height: 3rem; border-radius: .82rem; background: #fff0e7; color: #d9612a; }
.manage-page__overview-icon svg { width: 1.55rem; height: 1.55rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.45; }
.manage-page__overview-title > div { display: grid; gap: .16rem; min-width: 0; }
.manage-page__overview-title small { color: #d36a38; font-size: .56rem; font-weight: 800; letter-spacing: .12em; }
.manage-page__overview-title strong { overflow: hidden; font-size: .88rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__overview-title span { overflow: hidden; color: #8d97a7; font-size: .61rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__status-card { display: grid; align-content: center; gap: .12rem; padding: .8rem; border: 1px solid #ead9cf; border-radius: .88rem; background: #fff8f3; text-align: center; }
.manage-page__status-card small { color: #9b8477; font-size: .57rem; }
.manage-page__status-card strong { color: #cf5c29; font-size: .98rem; }
.manage-page__status-card span { color: #a49388; font-size: .56rem; }
.manage-page__status-card[data-status="APPROVED"],
.manage-page__status-card[data-status="PUBLISHED"] { border-color: #c8e2d1; background: #f1faf4; }
.manage-page__status-card[data-status="APPROVED"] strong,
.manage-page__status-card[data-status="PUBLISHED"] strong { color: #348257; }
.manage-page__status-card[data-status="PENDING"] { border-color: #d7dded; background: #f2f5fb; }
.manage-page__status-card[data-status="PENDING"] strong { color: #5573a1; }
.manage-page__status-card.is-empty { border-color: #e2e5eb; background: #f9fafb; }
.manage-page__status-card.is-empty strong { color: #7b8698; }
.manage-page__overview-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); overflow: hidden; border: 1px solid var(--manage-border); border-radius: .88rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.manage-page__overview-metrics article { display: grid; grid-template-columns: auto auto; align-content: center; align-items: baseline; justify-content: center; gap: .05rem .2rem; min-width: 0; min-height: 5rem; padding: .6rem; border-left: 1px solid #edf0f4; text-align: center; }
.manage-page__overview-metrics article:first-child { border-left: 0; }
.manage-page__overview-metrics small { grid-column: 1 / -1; color: #8f99a8; font-size: .57rem; }
.manage-page__overview-metrics strong { font-size: 1.15rem; }
.manage-page__overview-metrics span { color: #9aa3b1; font-size: .54rem; }
.manage-page__command-bar { display: flex; align-items: flex-end; gap: .8rem; padding: .78rem .85rem; border: 1px solid var(--manage-border); border-radius: .82rem; background: #fff; }
.manage-page__command-bar > label { display: grid; flex: 1; gap: .3rem; min-width: 13rem; color: #7b8698; font-size: .62rem; font-weight: 650; }
.manage-page__command-bar input { width: 100%; min-height: 2.35rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: 0 .7rem; outline: 0; color: #354054; font-size: .72rem; }
.manage-page__command-bar input:focus { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; }
.manage-page__command-bar > div { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: .42rem; }
.manage-page__command-bar button,
.manage-page__node-actions button { display: inline-flex; align-items: center; justify-content: center; gap: .32rem; min-height: 2.35rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: .52rem .72rem; background: #fff; color: #647086; font-size: .67rem; font-weight: 720; cursor: pointer; }
.manage-page__command-bar button svg,
.manage-page__node-actions button svg { width: .78rem; height: .78rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.manage-page__command-bar .is-primary { border-color: #cf5926; background: linear-gradient(135deg, #ed7a42, #cf5926); color: #fff; box-shadow: 0 7px 16px rgb(202 82 31 / 16%); }
.manage-page__command-bar .is-danger,
.manage-page__node-actions .is-danger { border-color: #efd0ca; color: #b84a3c; }
.manage-page__command-bar .is-publish { border-color: #39865a; background: linear-gradient(135deg, #52a56e, #337e52); color: #fff; box-shadow: 0 7px 16px rgb(47 124 80 / 16%); }
.manage-page__architecture-card { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; min-height: 41rem; border: 1px solid var(--manage-border); border-radius: .92rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.manage-page__architecture-card > header { display: flex; align-items: center; justify-content: space-between; gap: .8rem; padding: .75rem .88rem; border-bottom: 1px solid #e8ebf0; }
.manage-page__architecture-card > header > div:first-child { display: grid; gap: .1rem; }
.manage-page__architecture-card > header strong { font-size: .8rem; }
.manage-page__architecture-card > header span { color: #929baa; font-size: .6rem; }
.manage-page__chart-tools { display: flex; align-items: center; gap: .36rem; }
.manage-page__chart-tools label { display: grid; grid-template-columns: .85rem minmax(8rem, 12rem) auto; align-items: center; gap: .28rem; min-height: 2rem; padding: 0 .5rem; border: 1px solid #dfe3e9; border-radius: .55rem; background: #f9fafb; }
.manage-page__chart-tools label:focus-within { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; background: #fff; }
.manage-page__chart-tools label svg { width: .76rem; height: .76rem; fill: none; stroke: #929baa; stroke-linecap: round; stroke-width: 1.4; }
.manage-page__chart-tools input { min-width: 0; border: 0; outline: 0; background: transparent; color: #3b4659; font-size: .64rem; }
.manage-page__chart-tools label button { border: 0; padding: 0; background: transparent; color: #9aa3b1; cursor: pointer; }
.manage-page__chart-tools select { min-height: 2rem; border: 1px solid #dfe3e9; border-radius: .55rem; padding: 0 .45rem; outline: 0; background: #fff; color: #657085; font-size: .64rem; }
.manage-page__chart-tools > button { display: grid; place-items: center; width: 2rem; height: 2rem; border: 1px solid #dfe3e9; border-radius: .52rem; background: #fff; color: #6b7688; cursor: pointer; }
.manage-page__chart-tools > span { min-width: 2.7rem; text-align: center; }
.manage-page__chart-stage { overflow: auto; min-height: 34rem; background: #f8f9fb; }
.manage-page__chart-scale { min-width: max-content; transform: scale(var(--chart-scale)); transform-origin: top left; transition: transform .18s ease; }
.manage-page__architecture-card > footer { display: flex; align-items: center; justify-content: space-between; gap: .8rem; min-height: 3rem; padding: .58rem .85rem; border-top: 1px solid #e8ebf0; background: #fff; color: #8d97a7; font-size: .61rem; }
.manage-page__node-actions { display: flex; align-items: center; gap: .38rem; }
.manage-page__node-actions button { min-height: 2.05rem; padding: .4rem .6rem; }
.manage-page__architecture-card > footer em { color: #9aa3b1; font-style: normal; }
.manage-page__lower-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(18rem, .85fr); gap: .75rem; }
.manage-page__changes,
.manage-page__pending { display: grid; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; min-height: 15rem; border: 1px solid var(--manage-border); border-radius: .85rem; background: #fff; }
.manage-page__changes > header,
.manage-page__pending > header { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .72rem .82rem; border-bottom: 1px solid #edf0f4; }
.manage-page__changes > header > div,
.manage-page__pending > header > div { display: grid; gap: .08rem; }
.manage-page__changes > header strong,
.manage-page__pending > header strong { font-size: .76rem; }
.manage-page__changes > header span,
.manage-page__pending > header span { color: #949dac; font-size: .58rem; }
.manage-page__changes > header em,
.manage-page__pending > header em { padding: .2rem .4rem; border-radius: 999px; background: #fff0e8; color: #cd5926; font-size: .57rem; font-style: normal; font-weight: 750; }
.manage-page__change-list,
.manage-page__pending-list { display: grid; align-content: start; gap: .4rem; overflow: auto; max-height: 21rem; padding: .68rem; scrollbar-color: #d0d5dd transparent; scrollbar-width: thin; }
.manage-page__change-list article { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: .5rem; padding: .58rem .62rem; border: 1px solid #e8ebf0; border-radius: .64rem; background: #fff; }
.manage-page__change-list article > span { padding: .18rem .35rem; border-radius: 999px; background: #eef2f7; color: #6f7b90; font-size: .53rem; font-weight: 750; }
.manage-page__change-list article > span[data-kind="ADDED"] { background: #eaf8ef; color: #398157; }
.manage-page__change-list article > span[data-kind="UPDATED"] { background: #eaf3ff; color: #4775ac; }
.manage-page__change-list article > span[data-kind="REMOVED"] { background: #fff0ee; color: #b64a3d; }
.manage-page__change-list p { overflow: hidden; margin: 0; color: #59657a; font-size: .65rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__change-list small { color: #9aa3b1; font-size: .55rem; }
.manage-page__pending-list > button { display: grid; grid-template-columns: 2.15rem minmax(0, 1fr) auto .65rem; align-items: center; gap: .55rem; min-height: 3.3rem; border: 1px solid #e6e9ee; border-radius: .68rem; padding: .5rem .58rem; background: #fff; text-align: left; cursor: pointer; }
.manage-page__pending-list > button:hover,
.manage-page__pending-list > button.is-active { border-color: #edb79d; background: #fffaf7; }
.manage-page__pending-list > button > span { display: grid; place-items: center; width: 2.15rem; height: 2.15rem; border-radius: .6rem; background: #eef3fa; color: #5b76a0; font-size: .57rem; font-weight: 800; }
.manage-page__pending-list > button > div { display: grid; gap: .08rem; min-width: 0; }
.manage-page__pending-list strong { overflow: hidden; color: #3b4659; font-size: .67rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__pending-list small { overflow: hidden; color: #949dac; font-size: .55rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__pending-list i { padding: .17rem .34rem; border-radius: 999px; background: #eef2f7; color: #68768d; font-size: .52rem; font-style: normal; font-weight: 750; }
.manage-page__pending-list b { color: #a4adba; }
.manage-page__empty { display: grid; place-items: center; gap: .25rem; min-height: 10rem; color: #9aa3b1; text-align: center; }
.manage-page__empty svg { width: 2.2rem; height: 2.2rem; fill: none; stroke: #bdc4cf; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.3; }
.manage-page__empty strong { color: #687489; font-size: .71rem; }
.manage-page__empty span { font-size: .58rem; }
.manage-page__empty.is-small { min-height: 7rem; }
.manage-page__review-bar { position: sticky; z-index: 15; bottom: .8rem; display: grid; grid-template-columns: minmax(11rem, .8fr) minmax(15rem, 1.4fr) auto auto; align-items: center; gap: .6rem; padding: .75rem .82rem; border: 1px solid #d8deea; border-radius: .82rem; background: rgb(255 255 255 / 96%); box-shadow: 0 12px 32px rgb(33 45 65 / 13%); backdrop-filter: blur(12px); }
.manage-page__review-bar > div { display: grid; gap: .1rem; }
.manage-page__review-bar strong { font-size: .73rem; }
.manage-page__review-bar span { color: #929baa; font-size: .56rem; }
.manage-page__review-bar textarea { min-height: 2.9rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: .55rem .62rem; outline: 0; color: #3d485b; font-size: .64rem; resize: vertical; }
.manage-page__review-bar textarea:focus { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; }
.manage-page__review-bar button { min-height: 2.4rem; border-radius: .58rem; padding: .5rem .72rem; font-size: .65rem; font-weight: 750; cursor: pointer; }
.manage-page__review-bar .is-reject { border: 1px solid #efcac3; background: #fff4f2; color: #ae4437; }
.manage-page__review-bar .is-approve { border: 1px solid #39865a; background: linear-gradient(135deg, #52a56e, #337e52); color: #fff; }
.manage-page__drawer-backdrop { position: fixed; z-index: 70; inset: 0; display: flex; justify-content: flex-end; background: rgb(23 31 45 / 28%); backdrop-filter: blur(2px); }
.manage-page__drawer { display: grid; align-content: start; gap: .8rem; overflow: auto; width: min(39rem, 96vw); height: 100%; padding: .95rem 1rem 1.25rem; background: #f7f8fa; box-shadow: -18px 0 50px rgb(23 31 45 / 18%); animation: manage-drawer-in .22s ease both; scrollbar-color: #ccd2dc transparent; scrollbar-width: thin; }
.manage-page__drawer > header { position: sticky; z-index: 4; top: -.95rem; display: flex; align-items: center; justify-content: space-between; gap: .5rem; min-height: 2.8rem; margin: -.95rem -1rem 0; padding: .7rem 1rem; border-bottom: 1px solid #e6e9ee; background: rgb(255 255 255 / 96%); backdrop-filter: blur(10px); }
.manage-page__drawer-crumbs { display: flex; align-items: center; overflow: hidden; gap: .25rem; min-width: 0; color: #a0a8b5; font-size: .6rem; }
.manage-page__drawer-crumbs button { overflow: hidden; border: 0; padding: 0; background: transparent; color: #7d8798; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.manage-page__drawer-crumbs button:hover { color: var(--manage-orange); }
.manage-page__drawer-crumbs b { color: #5f6b80; }
.manage-page__drawer-close { display: grid; flex: 0 0 auto; place-items: center; width: 1.9rem; height: 1.9rem; border: 1px solid #dfe3e9; border-radius: 50%; background: #fff; color: #7c8798; font-size: 1rem; cursor: pointer; }
.manage-page__drawer-identity { display: grid; grid-template-columns: 3.2rem minmax(0, 1fr) auto; align-items: center; gap: .75rem; padding: .85rem; border: 1px solid #eadfd9; border-radius: .84rem; background: linear-gradient(135deg, #fff8f3, #fff); }
.manage-page__drawer-identity > span { display: grid; place-items: center; width: 3.2rem; height: 3.2rem; border-radius: .82rem; background: #fff0e8; color: #d75f29; font-size: .76rem; font-weight: 800; }
.manage-page__drawer-identity > span[data-type="CENTER"] { background: #e8f7ff; color: #267ea7; }
.manage-page__drawer-identity > span[data-type="DEPARTMENT"] { background: #e9f8f0; color: #338260; }
.manage-page__drawer-identity > div { display: grid; gap: .1rem; min-width: 0; }
.manage-page__drawer-identity p { display: flex; align-items: center; gap: .3rem; margin: 0; color: #8f99a8; font-size: .6rem; }
.manage-page__drawer-identity p i { padding: .13rem .32rem; border-radius: 999px; background: #f1f2f5; color: #8d96a4; font-style: normal; font-weight: 700; }
.manage-page__drawer-identity p i.is-on { background: #e9f8ef; color: #3c8657; }
.manage-page__drawer-identity h2 { overflow: hidden; margin: 0; font-size: 1.12rem; text-overflow: ellipsis; white-space: nowrap; }
.manage-page__drawer-identity small { color: #969fad; font-size: .63rem; }
.manage-page__drawer-identity em { padding: .2rem .4rem; border-radius: 999px; background: #fff0e8; color: #cc5926; font-size: .56rem; font-style: normal; font-weight: 750; }
@keyframes manage-pulse { 50% { opacity: .42; transform: scale(.78); } }
@keyframes manage-drawer-in { from { opacity: 0; transform: translateX(2rem); } }
@media (max-width: 1200px) {
  .manage-page__overview { grid-template-columns: 1fr 10rem; }
  .manage-page__overview-metrics { grid-column: 1 / -1; }
}
@media (max-width: 920px) {
  .manage-page__header { align-items: flex-start; flex-direction: column; }
  .manage-page__command-bar { align-items: stretch; flex-direction: column; }
  .manage-page__command-bar > div { justify-content: flex-start; }
  .manage-page__architecture-card > header { align-items: flex-start; flex-direction: column; }
  .manage-page__chart-tools { width: 100%; flex-wrap: wrap; }
  .manage-page__chart-tools label { flex: 1; }
  .manage-page__lower-grid { grid-template-columns: 1fr; }
  .manage-page__review-bar { position: static; grid-template-columns: 1fr 1fr; }
  .manage-page__review-bar > div,
  .manage-page__review-bar textarea { grid-column: 1 / -1; }
}
@media (max-width: 680px) {
  .manage-page { padding: .8rem; }
  .manage-page__heading > div { align-items: flex-start; flex-direction: column; gap: .2rem; }
  .manage-page__overview { grid-template-columns: 1fr; }
  .manage-page__overview-metrics { grid-template-columns: repeat(2, 1fr); }
  .manage-page__overview-metrics article:nth-child(odd) { border-left: 0; }
  .manage-page__overview-metrics article:last-child { grid-column: 1 / -1; }
  .manage-page__chart-tools label { flex-basis: 100%; }
  .manage-page__architecture-card > footer { align-items: flex-start; flex-direction: column; }
  .manage-page__node-actions { flex-wrap: wrap; }
  .manage-page__review-bar { grid-template-columns: 1fr; }
  .manage-page__review-bar > div,
  .manage-page__review-bar textarea { grid-column: auto; }
  .manage-page__drawer-identity { grid-template-columns: 3rem minmax(0, 1fr); }
  .manage-page__drawer-identity em { grid-column: 1 / -1; justify-self: start; }
}
</style>
