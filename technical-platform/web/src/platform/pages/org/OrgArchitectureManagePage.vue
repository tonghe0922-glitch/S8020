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
import OrgArchitectureTree from './OrgArchitectureTree.vue'
import OrgNodeEditor from './OrgNodeEditor.vue'
import {
  architectureError,
  cloneNodes,
  commandFromNode,
  draftStatusLabel,
  emptyNodeCommand,
  newDraftNode,
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

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

function activate(value: OrgArchitectureDraft): void {
  draft.value = value
  title.value = value.title
  nodes.value = cloneNodes(value.snapshot)
  const first = nodes.value[0]
  if (first) selectNode(first.id)
  else startNew(null)
}

function selectNode(nodeId: string): void {
  const node = nodes.value.find((candidate) => candidate.id === nodeId)
  if (!node) return
  selectedId.value = node.id
  editingId.value = node.id
  editor.value = commandFromNode(node)
}

function startNew(parentId: string | null): void {
  selectedId.value = ''
  editingId.value = ''
  editor.value = emptyNodeCommand(parentId)
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
    notice.value = '服务端草稿已创建，正式组织尚未改变'
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
    selectNode(editingId.value)
    notice.value = '本地草稿已调整，请点击“保存草稿”写入服务端'
    return
  }
  const created = newDraftNode(editor.value)
  nodes.value.push(created)
  selectNode(created.id)
  notice.value = '新节点已加入本地草稿，请保存草稿'
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
  const first = nodes.value[0]
  if (first) selectNode(first.id)
  else startNew(null)
  notice.value = '节点已从本地草稿移除；发布时服务端仍会校验在岗成员保护'
}

async function saveDraft(): Promise<void> {
  if (!draft.value) return
  saving.value = true
  clearFeedback()
  try {
    const saved = await api.updateDraft(draft.value.id, title.value, draft.value.versionNo, nodes.value)
    activate(saved)
    notice.value = `草稿已保存，共 ${saved.changes.length} 项变更`
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
    notice.value = '变更清单已提交审批，正式组织尚未改变'
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
    notice.value = approved ? '审批已通过，可以执行发布' : '草稿已驳回并回到提交人'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function publishDraft(): Promise<void> {
  if (!draft.value) return
  if (!globalThis.confirm('发布后将以单事务写入正式组织，并立即对技术端和全员通讯录生效。确认发布？')) return
  saving.value = true
  clearFeedback()
  try {
    const published = await api.publishDraft(draft.value.id, draft.value.versionNo)
    activate(published)
    await loadPending()
    notice.value = '组织架构已发布，企业通讯录现已读取最新版本'
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
    notice.value = '草稿已废弃'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

function openPending(value: OrgArchitectureDraft): void {
  activate(value)
  reviewComment.value = ''
}

onMounted(() => void load())
</script>

<template>
  <section class="manage-page">
    <header class="manage-page__hero">
      <div>
        <p>工作端 · 草稿审批发布</p>
        <h1>公司架构管理</h1>
        <span>草稿修改不会直接影响正式组织；审批通过并发布后，才会实时同步到全员通讯录。</span>
      </div>
      <div v-if="draft" class="manage-page__status">
        <small>当前草稿</small>
        <strong>{{ draftStatusLabel(draft.status) }}</strong>
        <span>基线 V{{ draft.baseVersion }} · 草稿 V{{ draft.versionNo }}</span>
      </div>
    </header>

    <p v-if="error" class="manage-page__feedback is-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="manage-page__feedback is-success" role="status">{{ notice }}</p>

    <div class="manage-page__toolbar">
      <input v-model="title" maxlength="160" aria-label="草稿标题" placeholder="草稿标题">
      <button v-if="canEdit && !draft" type="button" :disabled="saving" @click="createDraft">创建草稿</button>
      <button v-if="editable" type="button" :disabled="saving" @click="saveDraft">保存草稿</button>
      <button v-if="editable" type="button" :disabled="saving || !draft?.changes.length" @click="submitDraft">提交审批</button>
      <button v-if="editable" type="button" :disabled="saving" @click="abandonDraft">废弃草稿</button>
      <button type="button" :disabled="loading" @click="load">刷新</button>
    </div>

    <div class="manage-page__layout">
      <aside class="manage-page__tree-panel">
        <div class="manage-page__panel-heading">
          <div><small>草稿组织树</small><strong>{{ nodes.length }} 个节点</strong></div>
          <div v-if="editable" class="manage-page__mini-actions">
            <button type="button" @click="startNew(null)">新增顶级</button>
            <button type="button" :disabled="!selected" @click="startNew(selected?.id ?? null)">新增下级</button>
            <button type="button" :disabled="!selected" @click="removeSelected">移除</button>
          </div>
        </div>
        <OrgArchitectureTree :nodes="nodes" :selected-id="selectedId" compact @select="selectNode" />
      </aside>

      <main class="manage-page__editor-panel">
        <OrgNodeEditor
          v-model="editor"
          :nodes="nodes"
          :editing-id="editingId"
          :disabled="!editable || saving"
          :title="editingId ? '调整草稿节点' : '新增草稿节点'"
          submit-label="应用到本地草稿"
          @submit="applyEditor"
          @cancel="selected ? selectNode(selected.id) : startNew(null)"
        />
      </main>

      <aside class="manage-page__flow-panel">
        <section>
          <small>变更清单</small>
          <strong>{{ draft?.changes.length ?? 0 }} 项</strong>
          <ul>
            <li v-for="change in draft?.changes ?? []" :key="`${change.kind}-${change.nodeId}`">
              <span :data-kind="change.kind">{{ change.kind }}</span>{{ change.summary }}
            </li>
            <li v-if="!draft?.changes.length" class="is-empty">保存草稿后生成正式 diff</li>
          </ul>
        </section>

        <section v-if="canReview">
          <small>待审批草稿</small>
          <button
            v-for="item in pending" :key="item.id" type="button" class="manage-page__draft-card"
            :class="{ 'is-active': item.id === draft?.id }" @click="openPending(item)"
          >
            <strong>{{ item.title }}</strong>
            <span>{{ item.changes.length }} 项变更 · V{{ item.versionNo }}</span>
          </button>
          <p v-if="!pending.length" class="is-empty">当前没有待审批草稿</p>
        </section>

        <section v-if="draft?.status === 'PENDING' && canReview">
          <label>审批意见<textarea v-model="reviewComment" rows="3" /></label>
          <div class="manage-page__review-actions">
            <button type="button" :disabled="saving" @click="review(false)">驳回</button>
            <button type="button" :disabled="saving" @click="review(true)">通过</button>
          </div>
        </section>

        <section v-if="draft?.status === 'APPROVED' && canPublish">
          <small>发布确认</small>
          <p>发布会校验草稿基线、组织层级和在岗成员保护，并在同一事务内写入正式组织。</p>
          <button type="button" class="manage-page__publish" :disabled="saving" @click="publishDraft">发布正式版本</button>
        </section>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.manage-page { display: grid; gap: 1rem; padding: clamp(1rem, 2vw, 1.6rem); }
.manage-page__hero { display: flex; justify-content: space-between; gap: 1.5rem; padding: 1.4rem; border: 1px solid var(--color-border, #e8ddd3); border-radius: 1.2rem; background: linear-gradient(135deg, #fff9f2, #fff); }
.manage-page__hero p { margin: 0 0 .35rem; color: var(--color-primary, #d76b2f); font-size: .76rem; font-weight: 800; letter-spacing: .1em; }
.manage-page__hero h1 { margin: 0 0 .4rem; }
.manage-page__hero span { color: var(--color-text-muted, #776b62); }
.manage-page__status { display: grid; align-content: center; min-width: 12rem; padding: .9rem 1rem; border-radius: .9rem; background: #fff; box-shadow: 0 10px 28px rgb(70 34 15 / 8%); }
.manage-page__status strong { font-size: 1.2rem; color: var(--color-primary, #d76b2f); }
.manage-page__status small, .manage-page__status span { color: var(--color-text-muted, #776b62); }
.manage-page__toolbar { display: flex; flex-wrap: wrap; gap: .55rem; }
.manage-page__toolbar input { min-width: min(25rem, 100%); flex: 1; border: 1px solid var(--color-border, #e8ddd3); border-radius: .7rem; padding: .65rem .8rem; font: inherit; }
.manage-page button { border: 1px solid var(--color-border, #e8ddd3); border-radius: .65rem; padding: .58rem .78rem; background: #fff; color: inherit; font: inherit; font-weight: 700; cursor: pointer; }
.manage-page button:hover { border-color: var(--color-primary, #d76b2f); }
.manage-page button:disabled { cursor: not-allowed; opacity: .5; }
.manage-page__layout { display: grid; grid-template-columns: minmax(17rem, 31%) minmax(22rem, 1fr) minmax(17rem, 27%); gap: .85rem; align-items: start; }
.manage-page__layout > * { min-width: 0; border: 1px solid var(--color-border, #e8ddd3); border-radius: 1rem; background: #fff; padding: .9rem; }
.manage-page__tree-panel { max-height: 70vh; overflow: auto; }
.manage-page__panel-heading { display: flex; justify-content: space-between; gap: .75rem; margin-bottom: .8rem; }
.manage-page__panel-heading > div:first-child { display: grid; }
.manage-page__panel-heading small, .manage-page__flow-panel small { color: var(--color-text-muted, #776b62); }
.manage-page__mini-actions { display: flex; flex-wrap: wrap; justify-content: end; gap: .35rem; }
.manage-page__mini-actions button { padding: .35rem .5rem; font-size: .75rem; }
.manage-page__flow-panel { display: grid; gap: .8rem; }
.manage-page__flow-panel section { display: grid; gap: .55rem; padding-bottom: .8rem; border-bottom: 1px solid var(--color-border, #e8ddd3); }
.manage-page__flow-panel section:last-child { border-bottom: 0; }
.manage-page__flow-panel ul { display: grid; gap: .4rem; margin: 0; padding: 0; list-style: none; }
.manage-page__flow-panel li { display: grid; grid-template-columns: auto 1fr; gap: .45rem; font-size: .8rem; }
.manage-page__flow-panel li span { align-self: start; padding: .1rem .3rem; border-radius: .35rem; background: #f5efe8; font-size: .65rem; font-weight: 800; }
.manage-page__draft-card { display: grid; text-align: left; }
.manage-page__draft-card span { color: var(--color-text-muted, #776b62); font-size: .75rem; }
.manage-page__draft-card.is-active { border-color: var(--color-primary, #d76b2f); }
.manage-page__flow-panel label { display: grid; gap: .35rem; font-size: .8rem; font-weight: 700; }
.manage-page__flow-panel textarea { border: 1px solid var(--color-border, #e8ddd3); border-radius: .6rem; padding: .55rem; font: inherit; }
.manage-page__review-actions { display: grid; grid-template-columns: 1fr 1fr; gap: .45rem; }
.manage-page__publish { background: var(--color-primary, #d76b2f) !important; color: #fff !important; }
.manage-page__feedback { margin: 0; padding: .75rem 1rem; border-radius: .75rem; }
.manage-page__feedback.is-error { background: #fff0ee; color: #8f2d24; }
.manage-page__feedback.is-success { background: #eef8f1; color: #20633a; }
.is-empty { margin: 0; color: var(--color-text-muted, #776b62); font-size: .8rem; }
@media (max-width: 1180px) { .manage-page__layout { grid-template-columns: minmax(18rem, 38%) 1fr; } .manage-page__flow-panel { grid-column: 1 / -1; } }
@media (max-width: 760px) { .manage-page__layout { grid-template-columns: 1fr; } .manage-page__flow-panel { grid-column: auto; } .manage-page__hero { flex-direction: column; } }
</style>
