<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type {
  OrgArchitectureDirectory,
  OrgArchitectureDirectoryMember,
  OrgArchitectureNode,
  OrgArchitectureNodeCommand,
} from '../../../contracts'
import { createOrgArchitectureApi } from '../../../services/org-architecture/org-architecture-api'
import { usePortalSessionStore } from '../../../session'
import OrgArchitectureTree from './OrgArchitectureTree.vue'
import OrgNodeEditor from './OrgNodeEditor.vue'
import {
  architectureError,
  commandFromNode,
  emptyNodeCommand,
  orgTypeLabel,
} from './org-architecture-view-model'

type DetailTab = 'overview' | 'members' | 'permissions'

interface OrgArchitectureTreeHandle {
  expandAll: () => void
  collapseAll: () => void
}

const session = usePortalSessionStore()
const api = createOrgArchitectureApi(session)
const router = useRouter()
const data = ref<OrgArchitectureDirectory>({ versionNo: 0, publishedAt: null, organizations: [], members: [] })
const selectedId = ref('')
const editingId = ref('')
const editor = ref<OrgArchitectureNodeCommand>(emptyNodeCommand())
const activeTab = ref<DetailTab>('overview')
const query = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const treeRef = ref<OrgArchitectureTreeHandle | null>(null)

const nodes = computed(() => data.value.organizations)
const selected = computed(() => nodes.value.find((node) => node.id === selectedId.value) ?? null)
const activeCount = computed(() => nodes.value.filter((node) => node.status === 'ACTIVE').length)
const memberCount = computed(() => data.value.members.length)
const centerCount = computed(() => nodes.value.filter((node) => node.orgType === 'CENTER').length)
const typeOptions = computed(() => Array.from(new Set(nodes.value.map((node) => node.orgType))))

const childNodes = computed(() => selected.value
  ? nodes.value.filter((node) => node.parentId === selected.value?.id)
  : [])

const selectedMembers = computed(() => selected.value
  ? data.value.members.filter((member) => member.orgId === selected.value?.id)
  : [])

const descendantIds = computed(() => {
  if (!selected.value) return new Set<string>()
  const ids = new Set<string>([selected.value.id])
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes.value) {
      if (node.parentId && ids.has(node.parentId) && !ids.has(node.id)) {
        ids.add(node.id)
        changed = true
      }
    }
  }
  return ids
})

const hierarchyMembers = computed(() => data.value.members.filter((member) => descendantIds.value.has(member.orgId)))
const selectedManager = computed<OrgArchitectureDirectoryMember | null>(() => {
  if (!selected.value?.managerEmployeeId) return null
  return data.value.members.find((member) => member.employeeId === selected.value?.managerEmployeeId) ?? null
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

const utilization = computed(() => {
  if (!selected.value?.headcountPlan) return 0
  return Math.min(999, Math.round((selected.value.memberCount / selected.value.headcountPlan) * 100))
})

const detailTitle = computed(() => editingId.value ? '编辑组织详情' : '新建组织详情')

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

async function load(preferredId = selectedId.value, resetFeedback = true): Promise<void> {
  loading.value = true
  if (resetFeedback) clearFeedback()
  try {
    data.value = await api.directory()
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
  activeTab.value = 'overview'
}

function startNew(parentId: string | null): void {
  selectedId.value = ''
  editingId.value = ''
  editor.value = emptyNodeCommand(parentId)
  activeTab.value = 'overview'
  clearFeedback()
}

function cancelEdit(): void {
  const current = selected.value
  if (current) selectNode(current.id)
  else {
    const first = nodes.value[0]
    if (first) selectNode(first.id)
    else startNew(null)
  }
}

async function save(): Promise<void> {
  saving.value = true
  clearFeedback()
  try {
    const updating = Boolean(editingId.value)
    const saved = updating
      ? await api.updateNode(editingId.value, editor.value)
      : await api.createNode(editor.value)
    await load(saved.id, false)
    notice.value = updating ? '组织详情已保存并立即生效。' : '新组织已创建并立即生效。'
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function toggleSelected(): Promise<void> {
  if (!selected.value) return
  const active = selected.value.status !== 'ACTIVE'
  const action = active ? '启用' : '停用'
  if (!globalThis.confirm(`${action}“${selected.value.orgName}”及其下级组织？`)) return
  saving.value = true
  clearFeedback()
  try {
    const saved = await api.toggleNode(selected.value.id, { active, cascade: true })
    await load(saved.id, false)
    notice.value = `${action}操作已生效。`
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    saving.value = false
  }
}

async function deleteSelected(): Promise<void> {
  if (!selected.value) return
  if (!globalThis.confirm(`确认删除“${selected.value.orgName}”？存在下级组织或在岗成员时，系统会阻止删除。`)) return
  saving.value = true
  clearFeedback()
  try {
    await api.deleteNode(selected.value.id)
    await load('', false)
    notice.value = '组织节点已软删除，审计记录仍完整保留。'
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

function initials(value: string): string {
  return value.trim().slice(-2)
}

function formatPublishedAt(value: string | null): string {
  if (!value) return '尚未记录发布时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => void load())
</script>

<template>
  <section class="org-page org-console" :aria-busy="loading">
    <header class="org-page__header-compact org-console__header">
      <div class="org-console__heading">
        <p>技术管理 / 企业基础配置 / 组织架构</p>
        <div>
          <h1>组织架构配置</h1>
          <span>统一维护正式组织、负责人、编制与授权入口</span>
        </div>
      </div>
      <div class="org-console__header-actions">
        <span class="org-console__sync">
          <i :class="{ 'is-loading': loading }" />
          {{ loading ? '正在同步真实数据' : `已同步 · ${formatPublishedAt(data.publishedAt)}` }}
        </span>
        <button type="button" class="is-secondary" :disabled="loading" @click="load()">
          <svg viewBox="0 0 16 16"><path d="M13.5 5.2V2.5l-1.2 1.2A5.7 5.7 0 1 0 13 10M13.5 2.5h-2.7" /></svg>
          刷新
        </button>
        <button type="button" class="is-primary" @click="startNew(null)">
          <svg viewBox="0 0 16 16"><path d="M8 2.5v11M2.5 8h11" /></svg>
          新增顶级组织
        </button>
      </div>
    </header>

    <p v-if="error" class="org-console__feedback is-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="org-console__feedback is-success" role="status">{{ notice }}</p>

    <div class="org-console__metrics" aria-label="组织架构指标">
      <article>
        <span class="is-orange"><svg viewBox="0 0 24 24"><path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4" /></svg></span>
        <div><small>组织节点</small><strong>{{ nodes.length }}</strong><em>完整组织树</em></div>
      </article>
      <article>
        <span class="is-green"><svg viewBox="0 0 24 24"><path d="m5 12.5 4 4L19 6.5" /></svg></span>
        <div><small>启用节点</small><strong>{{ activeCount }}</strong><em>{{ nodes.length ? Math.round(activeCount / nodes.length * 100) : 0 }}% 正常运行</em></div>
      </article>
      <article>
        <span class="is-blue"><svg viewBox="0 0 24 24"><path d="M16 20v-1.7a3.8 3.8 0 0 0-3.8-3.8H7.3a3.8 3.8 0 0 0-3.8 3.8V20M9.8 10.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8ZM16.5 4.2a3.4 3.4 0 0 1 0 6.4M20.5 20v-1.7a3.8 3.8 0 0 0-2.8-3.7" /></svg></span>
        <div><small>在岗任职</small><strong>{{ memberCount }}</strong><em>来自正式通讯录</em></div>
      </article>
      <article>
        <span class="is-purple"><svg viewBox="0 0 24 24"><path d="M4 20V9h6v11M14 20V4h6v16M2.5 20h19" /></svg></span>
        <div><small>中心数量</small><strong>{{ centerCount }}</strong><em>跨中心统一治理</em></div>
      </article>
    </div>

    <div class="org-console__workspace">
      <aside class="org-console__directory">
        <div class="org-console__panel-head">
          <div><strong>组织目录</strong><span>{{ nodes.length }} 个节点</span></div>
          <div>
            <button type="button" title="全部展开" @click="treeRef?.expandAll()">
              <svg viewBox="0 0 16 16"><path d="M3 5.5h10M5 2.5 2 5.5l3 3M3 11h10" /></svg>
            </button>
            <button type="button" title="全部收起" @click="treeRef?.collapseAll()">
              <svg viewBox="0 0 16 16"><path d="M3 5.5h10M11 2.5l3 3-3 3M3 11h10" /></svg>
            </button>
          </div>
        </div>

        <label class="org-console__search">
          <svg viewBox="0 0 16 16"><circle cx="7" cy="7" r="4.3" /><path d="m10.3 10.3 3.2 3.2" /></svg>
          <input v-model="query" type="search" placeholder="搜索组织名称或编码">
          <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''">×</button>
        </label>

        <div class="org-console__filters">
          <select v-model="typeFilter" aria-label="组织类型筛选">
            <option value="">全部类型</option>
            <option v-for="type in typeOptions" :key="type" :value="type">{{ orgTypeLabel(type) }}</option>
          </select>
          <select v-model="statusFilter" aria-label="组织状态筛选">
            <option value="">全部状态</option>
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </div>

        <div class="org-console__tree-scroll">
          <OrgArchitectureTree
            ref="treeRef"
            :nodes="nodes"
            :selected-id="selectedId"
            :query="query"
            :type-filter="typeFilter"
            :status-filter="statusFilter"
            compact
            @select="selectNode"
          />
        </div>

        <button type="button" class="org-console__add-root" @click="startNew(null)">
          <svg viewBox="0 0 16 16"><path d="M8 2.5v11M2.5 8h11" /></svg>
          新增顶级组织
        </button>
      </aside>

      <main class="org-console__detail">
        <template v-if="selected || !editingId">
          <div class="org-console__detail-head">
            <div class="org-console__crumbs">
              <button type="button" @click="startNew(null)">组织架构</button>
              <template v-for="item in ancestorTrail" :key="item.id">
                <span>/</span><button type="button" @click="selectNode(item.id)">{{ item.orgName }}</button>
              </template>
              <template v-if="!editingId"><span>/</span><b>新增组织</b></template>
            </div>

            <div class="org-console__identity">
              <span class="org-console__identity-icon" :data-type="selected?.orgType ?? editor.orgType">
                <svg viewBox="0 0 24 24"><path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4M8 8.5h2M8 12h2M8 15.5h2" /></svg>
              </span>
              <div>
                <p>
                  <span>{{ orgTypeLabel(selected?.orgType ?? editor.orgType) }}</span>
                  <i :class="{ 'is-on': (selected?.status ?? editor.status) === 'ACTIVE' }">{{ (selected?.status ?? editor.status) === 'ACTIVE' ? '启用中' : '已停用' }}</i>
                </p>
                <h2>{{ selected?.orgName || '创建新组织' }}</h2>
                <small>{{ selected?.orgCode || '组织编码将在保存后自动生成' }}</small>
              </div>
              <div v-if="selected" class="org-console__detail-actions">
                <button type="button" @click="startNew(selected.id)">
                  <svg viewBox="0 0 16 16"><path d="M8 2.5v11M2.5 8h11" /></svg>新增下级
                </button>
                <button type="button" :disabled="saving" @click="toggleSelected">
                  {{ selected.status === 'ACTIVE' ? '级联停用' : '级联启用' }}
                </button>
                <button type="button" class="is-danger" :disabled="saving" @click="deleteSelected">软删除</button>
              </div>
            </div>
          </div>

          <nav class="org-console__tabs" aria-label="组织详情页签">
            <button type="button" :class="{ 'is-active': activeTab === 'overview' }" @click="activeTab = 'overview'">
              基本信息
            </button>
            <button type="button" :class="{ 'is-active': activeTab === 'members' }" :disabled="!selected" @click="activeTab = 'members'">
              组织成员 <span>{{ selectedMembers.length }}</span>
            </button>
            <button type="button" :class="{ 'is-active': activeTab === 'permissions' }" :disabled="!selected" @click="activeTab = 'permissions'">
              模块与授权
            </button>
          </nav>

          <div class="org-console__detail-body">
            <template v-if="activeTab === 'overview'">
              <div v-if="selected" class="org-console__summary-strip">
                <div><small>直属成员</small><strong>{{ selectedMembers.length }}</strong><span>人</span></div>
                <div><small>层级成员</small><strong>{{ hierarchyMembers.length }}</strong><span>人</span></div>
                <div><small>下级组织</small><strong>{{ childNodes.length }}</strong><span>个</span></div>
                <div><small>编制使用率</small><strong>{{ utilization }}</strong><span>%</span></div>
                <div class="is-manager">
                  <small>组织负责人</small>
                  <strong>{{ selectedManager?.displayName || '暂未设置' }}</strong>
                  <span>{{ selectedManager?.positionName || '—' }}</span>
                </div>
              </div>
              <OrgNodeEditor
                v-model="editor"
                :nodes="nodes"
                :editing-id="editingId"
                :disabled="saving"
                :title="detailTitle"
                :submit-label="saving ? '正在保存…' : '保存组织信息'"
                embedded
                @submit="save"
                @cancel="cancelEdit"
              />
            </template>

            <section v-else-if="activeTab === 'members'" class="org-console__members-panel">
              <header>
                <div><strong>直属组织成员</strong><span>数据来自已发布的企业通讯录与有效任职关系</span></div>
                <em>{{ selectedMembers.length }} 人</em>
              </header>
              <div class="org-console__member-list">
                <article v-for="member in selectedMembers" :key="`${member.employeeId}-${member.positionId}`">
                  <span>{{ initials(member.displayName) }}</span>
                  <div><strong>{{ member.displayName }}</strong><small>{{ member.employeeNo }}</small></div>
                  <div><small>岗位</small><strong>{{ member.positionName }}</strong></div>
                  <div><small>所属组织</small><strong>{{ member.orgName }}</strong></div>
                  <i v-if="member.employeeId === selected?.managerEmployeeId">负责人</i>
                </article>
                <div v-if="!selectedMembers.length" class="org-console__empty-state">
                  <svg viewBox="0 0 24 24"><path d="M16 20v-1.7a3.8 3.8 0 0 0-3.8-3.8H7.3a3.8 3.8 0 0 0-3.8 3.8V20M9.8 10.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8Z" /></svg>
                  <strong>当前组织暂无直属成员</strong><span>成员任职生效后会自动出现在此处</span>
                </div>
              </div>
            </section>

            <section v-else class="org-console__permissions-panel">
              <header><strong>模块与授权</strong><span>组织授权统一由权限中心维护，本页不复制第二套权限事实源</span></header>
              <div class="org-console__permission-card">
                <span><svg viewBox="0 0 24 24"><path d="M12 3 5 6v5c0 4.7 2.8 8 7 10 4.2-2 7-5.3 7-10V6zM9 12l2 2 4-4" /></svg></span>
                <div><strong>组织委派与模块授权</strong><p>配置该组织可使用的模块、岗位角色、数据范围和高风险操作边界。</p></div>
                <button type="button" @click="openDelegation">进入权限中心 <b>→</b></button>
              </div>
              <div class="org-console__permission-note">
                <svg viewBox="0 0 16 16"><path d="M8 1.7a6.3 6.3 0 1 0 0 12.6A6.3 6.3 0 0 0 8 1.7Zm0 4v3.2M8 11.5h.01" /></svg>
                技术端仅配置授权规则，不自动获得业务审批权或敏感字段读取权。
              </div>
            </section>
          </div>
        </template>
      </main>
    </div>
  </section>
</template>

<style scoped>
.org-page {
  --org-orange: #df6830;
  --org-orange-dark: #c95726;
  --org-border: #e4e7ed;
  --org-text: #273248;
  --org-muted: #8993a4;
  display: grid;
  gap: 1rem;
  min-width: 0;
  padding: 1.15rem clamp(1rem, 2vw, 1.55rem) 1.55rem;
  background: #f5f6f8;
  color: var(--org-text);
}
.org-page__header-compact { border-bottom: 0; }
.org-console__header { display: flex; align-items: center; justify-content: space-between; gap: 1.2rem; min-height: 3.7rem; padding: 0; }
.org-console__heading { display: grid; gap: .3rem; }
.org-console__heading > p { margin: 0; color: #9aa3b2; font-size: .64rem; }
.org-console__heading > div { display: flex; align-items: baseline; gap: .7rem; }
.org-console__heading h1 { margin: 0; font-size: 1.38rem; letter-spacing: -.02em; }
.org-console__heading span { color: #8d97a7; font-size: .72rem; }
.org-console__header-actions { display: flex; align-items: center; gap: .55rem; }
.org-console__sync { display: flex; align-items: center; gap: .38rem; margin-right: .2rem; color: #8b95a5; font-size: .66rem; white-space: nowrap; }
.org-console__sync i { width: .42rem; height: .42rem; border-radius: 50%; background: #4fa369; box-shadow: 0 0 0 3px #e8f7ed; }
.org-console__sync i.is-loading { background: #e99343; box-shadow: 0 0 0 3px #fff1df; animation: org-pulse 1s infinite; }
.org-console button { font: inherit; }
.org-console__header-actions button,
.org-console__detail-actions button { display: inline-flex; align-items: center; justify-content: center; gap: .35rem; min-height: 2.35rem; border-radius: .58rem; padding: .55rem .75rem; font-size: .7rem; font-weight: 720; cursor: pointer; }
.org-console__header-actions button svg,
.org-console__detail-actions button svg { width: .82rem; height: .82rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.55; }
.org-console__header-actions .is-secondary { border: 1px solid #dfe3e9; background: #fff; color: #637086; }
.org-console__header-actions .is-primary { border: 1px solid var(--org-orange-dark); background: linear-gradient(135deg, #ed7b43, var(--org-orange-dark)); color: #fff; box-shadow: 0 7px 16px rgb(202 82 31 / 18%); }
.org-console button:disabled { cursor: not-allowed; opacity: .48; }
.org-console__feedback { margin: 0; padding: .7rem .9rem; border: 1px solid; border-radius: .7rem; font-size: .74rem; }
.org-console__feedback.is-error { border-color: #f2c5be; background: #fff1ef; color: #a53a2d; }
.org-console__feedback.is-success { border-color: #bfe2cb; background: #eff9f2; color: #287047; }
.org-console__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .72rem; }
.org-console__metrics article { display: flex; align-items: center; gap: .72rem; min-width: 0; padding: .85rem .9rem; border: 1px solid var(--org-border); border-radius: .82rem; background: #fff; box-shadow: 0 4px 14px rgb(34 47 68 / 3%); }
.org-console__metrics article > span { display: grid; flex: 0 0 auto; place-items: center; width: 2.35rem; height: 2.35rem; border-radius: .68rem; }
.org-console__metrics article > span svg { width: 1.22rem; height: 1.22rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.55; }
.org-console__metrics .is-orange { background: #fff0e7; color: #d8612b; }
.org-console__metrics .is-green { background: #eaf8ef; color: #3e8b59; }
.org-console__metrics .is-blue { background: #eaf3ff; color: #4779b5; }
.org-console__metrics .is-purple { background: #f3edff; color: #7c5aad; }
.org-console__metrics article > div { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: .05rem .35rem; min-width: 0; }
.org-console__metrics small { grid-column: 1 / -1; color: #8d97a7; font-size: .64rem; }
.org-console__metrics strong { font-size: 1.28rem; line-height: 1; }
.org-console__metrics em { overflow: hidden; color: #9aa3b1; font-size: .59rem; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.org-console__workspace { display: grid; grid-template-columns: 19.5rem minmax(0, 1fr); gap: .82rem; min-height: 44rem; }
.org-console__directory,
.org-console__detail { min-width: 0; border: 1px solid var(--org-border); border-radius: .92rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.org-console__directory { display: grid; grid-template-rows: auto auto auto minmax(0, 1fr) auto; gap: .7rem; padding: .85rem; }
.org-console__panel-head { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .15rem .15rem .45rem; }
.org-console__panel-head > div:first-child { display: flex; align-items: baseline; gap: .4rem; }
.org-console__panel-head strong { font-size: .82rem; }
.org-console__panel-head span { color: #929cab; font-size: .62rem; }
.org-console__panel-head > div:last-child { display: flex; gap: .25rem; }
.org-console__panel-head button { display: grid; place-items: center; width: 1.7rem; height: 1.7rem; border: 1px solid #e2e6ec; border-radius: .48rem; background: #fff; color: #7d8798; cursor: pointer; }
.org-console__panel-head button:hover { border-color: #e8b79f; color: var(--org-orange); }
.org-console__panel-head svg { width: .78rem; height: .78rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.4; }
.org-console__search { display: grid; grid-template-columns: 1rem minmax(0, 1fr) auto; align-items: center; gap: .38rem; min-height: 2.25rem; padding: 0 .62rem; border: 1px solid #dfe3e9; border-radius: .6rem; background: #f9fafb; }
.org-console__search:focus-within { border-color: #e57b46; box-shadow: 0 0 0 3px #fff1e9; background: #fff; }
.org-console__search svg { width: .88rem; height: .88rem; fill: none; stroke: #929baa; stroke-linecap: round; stroke-width: 1.4; }
.org-console__search input { min-width: 0; border: 0; outline: none; background: transparent; color: #344055; font: inherit; font-size: .72rem; }
.org-console__search button { border: 0; background: transparent; color: #9aa3b2; cursor: pointer; }
.org-console__filters { display: grid; grid-template-columns: 1fr 1fr; gap: .45rem; }
.org-console__filters select { min-width: 0; border: 1px solid #e1e5eb; border-radius: .55rem; padding: .5rem .55rem; outline: none; background: #fff; color: #667186; font: inherit; font-size: .67rem; }
.org-console__tree-scroll { overflow: auto; min-height: 0; padding-right: .15rem; scrollbar-color: #d2d7df transparent; scrollbar-width: thin; }
.org-console__add-root { display: flex; align-items: center; justify-content: center; gap: .35rem; min-height: 2.35rem; border: 1px dashed #e1b39c; border-radius: .62rem; background: #fff8f4; color: #ce5c29; font-size: .7rem; font-weight: 720; cursor: pointer; }
.org-console__add-root svg { width: .8rem; height: .8rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-width: 1.5; }
.org-console__detail { display: grid; grid-template-rows: auto auto minmax(0, 1fr); overflow: hidden; }
.org-console__detail-head { padding: .9rem 1rem 0; }
.org-console__crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: .28rem; min-height: 1.35rem; color: #9aa3b2; font-size: .62rem; }
.org-console__crumbs button { border: 0; padding: 0; background: transparent; color: #7c8798; cursor: pointer; }
.org-console__crumbs button:hover { color: var(--org-orange); }
.org-console__crumbs b { color: #5f6b80; font-weight: 650; }
.org-console__identity { display: grid; grid-template-columns: 3.2rem minmax(0, 1fr) auto; align-items: center; gap: .8rem; padding: .78rem 0 1rem; }
.org-console__identity-icon { display: grid; place-items: center; width: 3.2rem; height: 3.2rem; border-radius: .84rem; background: #fff0e8; color: #d7612a; }
.org-console__identity-icon[data-type="CENTER"] { background: #eaf6ff; color: #2f7fa7; }
.org-console__identity-icon[data-type="DEPARTMENT"] { background: #eaf8f0; color: #358464; }
.org-console__identity-icon[data-type="GROUP"] { background: #fff7df; color: #92711f; }
.org-console__identity-icon svg { width: 1.65rem; height: 1.65rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.45; }
.org-console__identity > div:nth-child(2) { display: grid; gap: .15rem; }
.org-console__identity p { display: flex; align-items: center; gap: .35rem; margin: 0; }
.org-console__identity p > span { color: #8c96a6; font-size: .62rem; font-weight: 700; }
.org-console__identity p i { padding: .14rem .36rem; border-radius: 999px; background: #f1f2f5; color: #8c95a3; font-size: .57rem; font-style: normal; font-weight: 700; }
.org-console__identity p i.is-on { background: #e9f8ef; color: #388455; }
.org-console__identity h2 { margin: 0; font-size: 1.25rem; }
.org-console__identity small { color: #939cab; font-size: .67rem; }
.org-console__detail-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: .4rem; }
.org-console__detail-actions button { border: 1px solid #dfe3e9; background: #fff; color: #657085; }
.org-console__detail-actions button:hover { border-color: #e6ad91; color: #cf5a27; }
.org-console__detail-actions .is-danger { border-color: #f0d0cb; color: #b84b3d; }
.org-console__tabs { display: flex; align-items: flex-end; gap: 1.1rem; padding: 0 1rem; border-top: 1px solid #edf0f4; border-bottom: 1px solid #e7eaf0; background: #fbfcfd; }
.org-console__tabs button { position: relative; min-height: 2.75rem; border: 0; padding: 0 .12rem; background: transparent; color: #7f8999; font-size: .72rem; font-weight: 700; cursor: pointer; }
.org-console__tabs button::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; border-radius: 2px 2px 0 0; background: transparent; content: ''; }
.org-console__tabs button.is-active { color: #cf5927; }
.org-console__tabs button.is-active::after { background: var(--org-orange); }
.org-console__tabs button span { display: inline-grid; place-items: center; min-width: 1.1rem; height: 1.1rem; margin-left: .2rem; border-radius: 999px; background: #eef1f5; color: #778195; font-size: .57rem; }
.org-console__detail-body { overflow: auto; min-height: 0; padding: 1rem; background: #f8f9fb; scrollbar-color: #d1d7df transparent; scrollbar-width: thin; }
.org-console__summary-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)) minmax(10rem, 1.3fr); gap: .55rem; margin-bottom: .75rem; }
.org-console__summary-strip > div { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: .08rem .25rem; padding: .72rem .75rem; border: 1px solid #e6e9ef; border-radius: .72rem; background: #fff; }
.org-console__summary-strip small { grid-column: 1 / -1; color: #9099a8; font-size: .61rem; }
.org-console__summary-strip strong { font-size: 1rem; }
.org-console__summary-strip span { color: #929baa; font-size: .58rem; }
.org-console__summary-strip .is-manager { grid-template-columns: 1fr; }
.org-console__summary-strip .is-manager strong { overflow: hidden; font-size: .8rem; text-overflow: ellipsis; white-space: nowrap; }
.org-console__summary-strip .is-manager span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.org-console__members-panel,
.org-console__permissions-panel { display: grid; gap: .8rem; }
.org-console__members-panel > header,
.org-console__permissions-panel > header { display: flex; align-items: center; justify-content: space-between; gap: .8rem; padding: .25rem .1rem; }
.org-console__members-panel > header > div,
.org-console__permissions-panel > header { color: #8e98a8; font-size: .68rem; }
.org-console__members-panel > header > div { display: grid; gap: .15rem; }
.org-console__members-panel > header strong,
.org-console__permissions-panel > header strong { color: #344055; font-size: .88rem; }
.org-console__members-panel > header em { padding: .25rem .48rem; border-radius: 999px; background: #fff0e8; color: #cb5927; font-size: .64rem; font-style: normal; font-weight: 750; }
.org-console__member-list { display: grid; gap: .48rem; }
.org-console__member-list article { display: grid; grid-template-columns: 2.45rem minmax(8rem, 1fr) minmax(7rem, .8fr) minmax(8rem, 1fr) auto; align-items: center; gap: .72rem; padding: .75rem .82rem; border: 1px solid #e5e8ee; border-radius: .75rem; background: #fff; }
.org-console__member-list article > span { display: grid; place-items: center; width: 2.45rem; height: 2.45rem; border-radius: .7rem; background: #edf3fc; color: #5576a4; font-size: .7rem; font-weight: 800; }
.org-console__member-list article > div { display: grid; gap: .1rem; min-width: 0; }
.org-console__member-list article strong { overflow: hidden; color: #344055; font-size: .75rem; text-overflow: ellipsis; white-space: nowrap; }
.org-console__member-list article small { overflow: hidden; color: #949dac; font-size: .61rem; text-overflow: ellipsis; white-space: nowrap; }
.org-console__member-list article i { padding: .2rem .4rem; border-radius: 999px; background: #fff0e7; color: #cf5b28; font-size: .58rem; font-style: normal; font-weight: 750; }
.org-console__empty-state { display: grid; place-items: center; gap: .32rem; min-height: 18rem; color: #9aa3b1; text-align: center; }
.org-console__empty-state svg { width: 2.8rem; height: 2.8rem; fill: none; stroke: #bbc2cd; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.35; }
.org-console__empty-state strong { color: #667287; font-size: .86rem; }
.org-console__empty-state span { font-size: .67rem; }
.org-console__permission-card { display: grid; grid-template-columns: 3rem minmax(0, 1fr) auto; align-items: center; gap: .85rem; padding: 1rem; border: 1px solid #e2e6ec; border-radius: .85rem; background: #fff; }
.org-console__permission-card > span { display: grid; place-items: center; width: 3rem; height: 3rem; border-radius: .82rem; background: #fff0e8; color: #d8612b; }
.org-console__permission-card svg { width: 1.55rem; height: 1.55rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.org-console__permission-card > div { display: grid; gap: .22rem; }
.org-console__permission-card strong { font-size: .86rem; }
.org-console__permission-card p { margin: 0; color: #8993a4; font-size: .68rem; line-height: 1.6; }
.org-console__permission-card button { border: 1px solid #dc6830; border-radius: .58rem; padding: .58rem .72rem; background: #fff7f2; color: #cc5724; font-size: .68rem; font-weight: 750; cursor: pointer; }
.org-console__permission-card button b { margin-left: .25rem; }
.org-console__permission-note { display: flex; align-items: center; gap: .4rem; padding: .7rem .8rem; border-radius: .65rem; background: #eef4fb; color: #64758e; font-size: .66rem; }
.org-console__permission-note svg { flex: 0 0 auto; width: .9rem; height: .9rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.35; }
@keyframes org-pulse { 50% { opacity: .42; transform: scale(.78); } }
@media (max-width: 1180px) {
  .org-console__metrics { grid-template-columns: repeat(2, 1fr); }
  .org-console__workspace { grid-template-columns: 17rem minmax(0, 1fr); }
  .org-console__summary-strip { grid-template-columns: repeat(2, 1fr); }
  .org-console__summary-strip .is-manager { grid-column: 1 / -1; }
  .org-console__member-list article { grid-template-columns: 2.45rem minmax(8rem, 1fr) minmax(7rem, 1fr) auto; }
  .org-console__member-list article > div:nth-of-type(3) { display: none; }
}
@media (max-width: 880px) {
  .org-console__header { align-items: flex-start; flex-direction: column; }
  .org-console__header-actions { width: 100%; flex-wrap: wrap; }
  .org-console__sync { width: 100%; }
  .org-console__workspace { grid-template-columns: 1fr; }
  .org-console__directory { max-height: 32rem; }
  .org-console__detail { min-height: 42rem; }
  .org-console__identity { grid-template-columns: 3rem minmax(0, 1fr); }
  .org-console__detail-actions { grid-column: 1 / -1; justify-content: flex-start; }
}
@media (max-width: 620px) {
  .org-page { padding: .8rem; }
  .org-console__heading > div { align-items: flex-start; flex-direction: column; gap: .2rem; }
  .org-console__metrics { grid-template-columns: 1fr; }
  .org-console__tabs { overflow-x: auto; }
  .org-console__summary-strip { grid-template-columns: 1fr 1fr; }
  .org-console__member-list article { grid-template-columns: 2.35rem minmax(0, 1fr) auto; }
  .org-console__member-list article > div:nth-of-type(n+2) { display: none; }
  .org-console__permission-card { grid-template-columns: 2.7rem minmax(0, 1fr); }
  .org-console__permission-card button { grid-column: 1 / -1; }
}
</style>
