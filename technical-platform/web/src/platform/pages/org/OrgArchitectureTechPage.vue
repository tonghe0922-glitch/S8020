<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { OrgArchitectureNode, OrgArchitectureNodeCommand } from '../../../contracts'
import { createOrgArchitectureApi } from '../../../services/org-architecture/org-architecture-api'
import { usePortalSessionStore } from '../../../session'
import OrgArchitectureTree from './OrgArchitectureTree.vue'
import OrgNodeEditor from './OrgNodeEditor.vue'
import {
  architectureError,
  commandFromNode,
  emptyNodeCommand,
} from './org-architecture-view-model'

const session = usePortalSessionStore()
const api = createOrgArchitectureApi(session)
const router = useRouter()
const nodes = ref<OrgArchitectureNode[]>([])
const selectedId = ref('')
const editingId = ref('')
const editor = ref<OrgArchitectureNodeCommand>(emptyNodeCommand())
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const selected = computed(() => nodes.value.find((node) => node.id === selectedId.value) ?? null)
const activeCount = computed(() => nodes.value.filter((node) => node.status === 'ACTIVE').length)
const memberCount = computed(() => nodes.value.reduce((sum, node) => sum + node.memberCount, 0))

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

async function load(preferredId = selectedId.value): Promise<void> {
  loading.value = true
  clearFeedback()
  try {
    nodes.value = await api.tree()
    const next = nodes.value.find((node) => node.id === preferredId) ?? nodes.value[0] ?? null
    if (next) selectNode(next.id)
    else startNew(null)
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    loading.value = false
  }
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

async function save(): Promise<void> {
  saving.value = true
  clearFeedback()
  try {
    const saved = editingId.value
      ? await api.updateNode(editingId.value, editor.value)
      : await api.createNode(editor.value)
    notice.value = editingId.value ? '组织节点已更新并立即生效' : '组织节点已创建并立即生效'
    await load(saved.id)
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function toggleSelected(): Promise<void> {
  if (!selected.value) return
  const action = selected.value.status === 'ACTIVE' ? '停用' : '启用'
  if (!globalThis.confirm(`${action}“${selected.value.orgName}”及其下级组织？`)) return
  saving.value = true
  clearFeedback()
  try {
    const saved = await api.toggleNode(selected.value.id, {
      active: selected.value.status !== 'ACTIVE',
      cascade: true,
    })
    notice.value = `${action}已生效`
    await load(saved.id)
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function deleteSelected(): Promise<void> {
  if (!selected.value) return
  if (!globalThis.confirm(`确认删除“${selected.value.orgName}”？有下级或在岗成员时服务端会拒绝。`)) return
  saving.value = true
  clearFeedback()
  try {
    await api.deleteNode(selected.value.id)
    notice.value = '组织节点已软删除并保留审计记录'
    await load('')
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

function openDelegation(): void {
  if (!selected.value) return
  void router.push(`/tech/authz/orgs/${selected.value.id}`)
}

onMounted(() => void load())
</script>

<template>
  <section class="org-page">
    <header class="org-page__hero">
      <div>
        <p>技术端 · 单一事实源</p>
        <h1>组织架构配置</h1>
        <span>直接配置正式组织；保存后工作端和企业通讯录读取同一数据库。</span>
      </div>
      <div class="org-page__metrics" aria-label="组织架构指标">
        <strong>{{ nodes.length }}</strong><span>组织节点</span>
        <strong>{{ activeCount }}</strong><span>启用节点</span>
        <strong>{{ memberCount }}</strong><span>有效任职</span>
      </div>
    </header>

    <p v-if="error" class="org-page__feedback is-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="org-page__feedback is-success" role="status">{{ notice }}</p>

    <div class="org-page__toolbar">
      <button type="button" @click="startNew(null)">新增顶级组织</button>
      <button type="button" :disabled="!selected" @click="startNew(selected?.id ?? null)">新增下级</button>
      <button type="button" :disabled="!selected || saving" @click="toggleSelected">
        {{ selected?.status === 'ACTIVE' ? '级联停用' : '级联启用' }}
      </button>
      <button type="button" :disabled="!selected" @click="openDelegation">委派与模块授权</button>
      <button type="button" class="is-danger" :disabled="!selected || saving" @click="deleteSelected">软删除</button>
      <button type="button" :disabled="loading" @click="load()">刷新</button>
    </div>

    <div class="org-page__workspace" :aria-busy="loading">
      <aside>
        <OrgArchitectureTree :nodes="nodes" :selected-id="selectedId" @select="selectNode" />
      </aside>
      <main>
        <OrgNodeEditor
          v-model="editor"
          :nodes="nodes"
          :editing-id="editingId"
          :disabled="saving"
          :title="editingId ? '编辑正式组织' : '创建正式组织'"
          :submit-label="saving ? '正在保存…' : '保存并立即生效'"
          @submit="save"
          @cancel="selected ? selectNode(selected.id) : startNew(null)"
        />
      </main>
    </div>
  </section>
</template>

<style scoped>
.org-page { display: grid; gap: 1.1rem; padding: clamp(1rem, 2vw, 1.8rem); }
.org-page__hero { display: flex; align-items: end; justify-content: space-between; gap: 1.5rem; padding: 1.5rem; border-radius: 1.2rem; background: linear-gradient(135deg, #fff8f1, #fff); border: 1px solid var(--color-border, #e8ddd3); }
.org-page__hero p { margin: 0 0 .35rem; color: var(--color-primary, #d76b2f); font-size: .78rem; font-weight: 800; letter-spacing: .1em; }
.org-page__hero h1 { margin: 0 0 .45rem; font-size: clamp(1.65rem, 3vw, 2.35rem); }
.org-page__hero span { color: var(--color-text-muted, #776b62); }
.org-page__metrics { display: grid; grid-template-columns: repeat(3, auto); gap: .15rem 1rem; text-align: center; }
.org-page__metrics strong { font-size: 1.5rem; }
.org-page__metrics span { grid-row: 2; color: var(--color-text-muted, #776b62); font-size: .72rem; }
.org-page__toolbar { display: flex; flex-wrap: wrap; gap: .55rem; }
.org-page__toolbar button { border: 1px solid var(--color-border, #e8ddd3); border-radius: .7rem; padding: .6rem .85rem; background: #fff; font: inherit; font-weight: 700; cursor: pointer; }
.org-page__toolbar button:hover { border-color: var(--color-primary, #d76b2f); }
.org-page__toolbar .is-danger { color: #a73228; }
.org-page__toolbar button:disabled { cursor: not-allowed; opacity: .5; }
.org-page__workspace { display: grid; grid-template-columns: minmax(20rem, 42%) minmax(0, 1fr); gap: 1rem; align-items: start; }
.org-page__workspace > aside, .org-page__workspace > main { min-width: 0; border: 1px solid var(--color-border, #e8ddd3); border-radius: 1rem; background: #fff; padding: 1rem; }
.org-page__workspace > aside { max-height: 68vh; overflow: auto; }
.org-page__feedback { margin: 0; padding: .75rem 1rem; border-radius: .75rem; }
.org-page__feedback.is-error { background: #fff0ee; color: #8f2d24; }
.org-page__feedback.is-success { background: #eef8f1; color: #20633a; }
@media (max-width: 980px) { .org-page__workspace { grid-template-columns: 1fr; } .org-page__hero { align-items: start; flex-direction: column; } }
</style>
