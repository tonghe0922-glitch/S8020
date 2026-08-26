<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type {
  OrgArchitectureDirectory,
  OrgArchitectureDirectoryMember,
  OrgArchitectureNode,
} from '../../../contracts'
import { createOrgArchitectureApi } from '../../../services/org-architecture/org-architecture-api'
import { usePortalSessionStore } from '../../../session'
import OrgArchitectureChart from './OrgArchitectureChart.vue'
import { architectureError, orgTypeLabel } from './org-architecture-view-model'

const props = defineProps<{ mode: 'architecture' | 'directory' }>()
const session = usePortalSessionStore()
const api = createOrgArchitectureApi(session)
const data = ref<OrgArchitectureDirectory>({ versionNo: 0, publishedAt: null, organizations: [], members: [] })
const selectedOrgId = ref('')
const directoryOrgId = ref('')
const detailOpen = ref(false)
const query = ref('')
const typeFilter = ref('')
const chartScale = ref(1)
const loading = ref(false)
const error = ref('')

const selectedOrg = computed(() => data.value.organizations.find((node) => node.id === selectedOrgId.value) ?? null)
const typeOptions = computed(() => Array.from(new Set(data.value.organizations.map((node) => node.orgType))))
const activeOrganizations = computed(() => data.value.organizations.filter((node) => node.status === 'ACTIVE').length)
const centerCount = computed(() => data.value.organizations.filter((node) => node.orgType === 'CENTER').length)
const departmentCount = computed(() => data.value.organizations.filter((node) => node.orgType === 'DEPARTMENT').length)

const filteredMembers = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  return data.value.members.filter((member) => {
    if (directoryOrgId.value && member.orgId !== directoryOrgId.value) return false
    if (!keyword) return true
    return [member.displayName, member.employeeNo, member.orgName, member.positionName]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
  })
})

function organizationMatchesKeyword(node: OrgArchitectureNode, keyword: string): boolean {
  if (!keyword) return true
  return [node.orgName, node.orgCode, orgTypeLabel(node.orgType)]
    .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
}

function organizationMatchesType(node: OrgArchitectureNode): boolean {
  return !typeFilter.value || node.orgType === typeFilter.value
}

function organizationMatchesFilters(node: OrgArchitectureNode, keyword: string): boolean {
  return organizationMatchesKeyword(node, keyword) && organizationMatchesType(node)
}

function addOrganizationTrail(
  node: OrgArchitectureNode,
  nodeMap: ReadonlyMap<string, OrgArchitectureNode>,
  visibleIds: Set<string>,
): void {
  visibleIds.add(node.id)
  let current = node
  while (current.parentId) {
    visibleIds.add(current.parentId)
    const parent = nodeMap.get(current.parentId)
    if (!parent) return
    current = parent
  }
}

const displayedOrganizations = computed(() => {
  const keyword = props.mode === 'architecture' ? query.value.trim().toLocaleLowerCase('zh-CN') : ''
  if (!keyword && !typeFilter.value) return data.value.organizations

  const nodeMap = new Map(data.value.organizations.map((node) => [node.id, node]))
  const visibleIds = new Set<string>()
  data.value.organizations
    .filter((node) => organizationMatchesFilters(node, keyword))
    .forEach((node) => addOrganizationTrail(node, nodeMap, visibleIds))
  return data.value.organizations.filter((node) => visibleIds.has(node.id))
})

const selectedChildren = computed(() => selectedOrg.value
  ? data.value.organizations
    .filter((node) => node.parentId === selectedOrg.value?.id)
    .sort((left, right) => left.sortNo - right.sortNo)
  : [])

const selectedMembers = computed(() => selectedOrg.value
  ? data.value.members.filter((member) => member.orgId === selectedOrg.value?.id)
  : [])

const selectedManager = computed<OrgArchitectureDirectoryMember | null>(() => {
  if (!selectedOrg.value?.managerEmployeeId) return null
  return data.value.members.find((member) => member.employeeId === selectedOrg.value?.managerEmployeeId) ?? null
})

const ancestorTrail = computed<OrgArchitectureNode[]>(() => {
  if (!selectedOrg.value) return []
  const nodes = data.value.organizations
  const trail: OrgArchitectureNode[] = []
  const visited = new Set<string>()
  let current: OrgArchitectureNode | undefined = selectedOrg.value
  while (current && !visited.has(current.id)) {
    trail.unshift(current)
    visited.add(current.id)
    current = current.parentId ? nodes.find((node) => node.id === current?.parentId) : undefined
  }
  return trail
})

const selectedUtilization = computed(() => {
  if (!selectedOrg.value?.headcountPlan) return 0
  return Math.min(999, Math.round(selectedOrg.value.memberCount / selectedOrg.value.headcountPlan * 100))
})

watch(
  () => props.mode,
  () => {
    query.value = ''
    typeFilter.value = ''
    detailOpen.value = false
  },
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.directory()
    if (selectedOrgId.value && !data.value.organizations.some((node) => node.id === selectedOrgId.value)) {
      selectedOrgId.value = ''
      detailOpen.value = false
    }
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    loading.value = false
  }
}

function openOrg(nodeId: string): void {
  if (!data.value.organizations.some((node) => node.id === nodeId)) return
  selectedOrgId.value = nodeId
  detailOpen.value = true
}

function closeDetail(): void {
  detailOpen.value = false
}

function focusFirstMatch(): void {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  const match = data.value.organizations.find((node) => {
    const matchesKeyword = !keyword || [node.orgName, node.orgCode]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
    return matchesKeyword && (!typeFilter.value || node.orgType === typeFilter.value)
  })
  if (match) openOrg(match.id)
}

function zoom(delta: number): void {
  chartScale.value = Math.min(1.25, Math.max(.7, Number((chartScale.value + delta).toFixed(2))))
}

function initials(value: string): string {
  return value.trim().slice(-2)
}

function formatDate(value: string | null): string {
  if (!value) return '暂无更新时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

onMounted(() => void load())
</script>

<template>
  <section class="directory-page" :class="{ 'is-architecture': mode === 'architecture' }" :aria-busy="loading">
    <header class="directory-page__header-compact">
      <div class="directory-page__heading">
        <p>{{ mode === 'architecture' ? '中心事务 / 企业信息 / 组织架构' : '中心事务 / 企业信息 / 企业通讯录' }}</p>
        <div>
          <h1>{{ mode === 'architecture' ? '企业组织架构' : '企业通讯录' }}</h1>
          <span>{{ mode === 'architecture' ? '查看企业组织层级、职责边界与在岗成员' : '按组织、姓名、工号或岗位查找在岗同事' }}</span>
        </div>
      </div>
      <div class="directory-page__header-actions">
        <span><i :class="{ 'is-loading': loading }" />{{ loading ? '正在同步' : `数据更新于 ${formatDate(data.publishedAt)}` }}</span>
        <button type="button" :disabled="loading" @click="load">
          <svg viewBox="0 0 16 16"><path d="M13.5 5.2V2.5l-1.2 1.2A5.7 5.7 0 1 0 13 10M13.5 2.5h-2.7" /></svg>
          刷新数据
        </button>
      </div>
    </header>

    <p v-if="error" class="directory-page__error" role="alert">{{ error }}</p>

    <template v-if="mode === 'architecture'">
      <section class="directory-page__overview">
        <div class="directory-page__overview-copy">
          <span class="directory-page__overview-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4M8 8.5h2M8 12h2M8 15.5h2" /></svg>
          </span>
          <div><small>ORGANIZATION OVERVIEW</small><strong>上金谷企业组织体系</strong><span>点击组织卡片，可查看职责、负责人、编制、成员与下级组织详情。</span></div>
        </div>
        <div class="directory-page__overview-metrics">
          <article><small>组织节点</small><strong>{{ data.organizations.length }}</strong><span>个</span></article>
          <article><small>在岗成员</small><strong>{{ data.members.length }}</strong><span>人</span></article>
          <article><small>管理中心</small><strong>{{ centerCount }}</strong><span>个</span></article>
          <article><small>业务部门</small><strong>{{ departmentCount }}</strong><span>个</span></article>
          <article><small>启用率</small><strong>{{ data.organizations.length ? Math.round(activeOrganizations / data.organizations.length * 100) : 0 }}</strong><span>%</span></article>
        </div>
      </section>

      <section class="directory-page__architecture-card">
        <header>
          <div>
            <strong>组织架构图</strong>
            <span>共 {{ displayedOrganizations.length }} 个可见节点 · 拖动底部滚动条查看完整层级</span>
          </div>
          <div class="directory-page__chart-tools">
            <label>
              <svg viewBox="0 0 16 16"><circle cx="7" cy="7" r="4.3" /><path d="m10.3 10.3 3.2 3.2" /></svg>
              <input v-model="query" type="search" placeholder="搜索组织名称或编码" @keyup.enter="focusFirstMatch">
              <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''">×</button>
            </label>
            <select v-model="typeFilter" aria-label="按组织类型筛选">
              <option value="">全部类型</option>
              <option v-for="type in typeOptions" :key="type" :value="type">{{ orgTypeLabel(type) }}</option>
            </select>
            <button type="button" title="缩小" :disabled="chartScale <= .7" @click="zoom(-.1)">−</button>
            <span>{{ Math.round(chartScale * 100) }}%</span>
            <button type="button" title="放大" :disabled="chartScale >= 1.25" @click="zoom(.1)">＋</button>
          </div>
        </header>
        <div class="directory-page__chart-stage">
          <div class="directory-page__chart-scale" :style="{ '--chart-scale': String(chartScale) }">
            <OrgArchitectureChart
              :nodes="displayedOrganizations"
              :selected-id="selectedOrgId"
              @select="openOrg"
            />
          </div>
        </div>
        <footer>
          <span><i class="is-company" />公司/管理</span>
          <span><i class="is-center" />中心</span>
          <span><i class="is-department" />部门</span>
          <span><i class="is-group" />小组</span>
          <em>组织数据来自当前已发布架构，员工端只读展示。</em>
        </footer>
      </section>

      <div v-if="detailOpen && selectedOrg" class="directory-page__drawer-backdrop" @click.self="closeDetail">
        <aside class="directory-page__drawer" role="dialog" aria-modal="true" :aria-label="`${selectedOrg.orgName}详情`">
          <header>
            <div class="directory-page__drawer-crumbs">
              <template v-for="(item, index) in ancestorTrail" :key="item.id">
                <span v-if="index">/</span><button type="button" @click="openOrg(item.id)">{{ item.orgName }}</button>
              </template>
            </div>
            <button type="button" class="directory-page__drawer-close" aria-label="关闭详情" @click="closeDetail">×</button>
          </header>

          <div class="directory-page__drawer-identity">
            <span :data-type="selectedOrg.orgType">{{ initials(selectedOrg.orgName) }}</span>
            <div>
              <p><i :class="{ 'is-on': selectedOrg.status === 'ACTIVE' }">{{ selectedOrg.status === 'ACTIVE' ? '启用中' : '已停用' }}</i>{{ orgTypeLabel(selectedOrg.orgType) }}</p>
              <h2>{{ selectedOrg.orgName }}</h2>
              <small>{{ selectedOrg.orgCode }}</small>
            </div>
          </div>

          <div class="directory-page__drawer-metrics">
            <article><small>在岗人数</small><strong>{{ selectedOrg.memberCount }}</strong><span>人</span></article>
            <article><small>规划编制</small><strong>{{ selectedOrg.headcountPlan }}</strong><span>人</span></article>
            <article><small>编制使用</small><strong>{{ selectedUtilization }}</strong><span>%</span></article>
            <article><small>下级组织</small><strong>{{ selectedChildren.length }}</strong><span>个</span></article>
          </div>

          <section class="directory-page__drawer-section">
            <header><span><svg viewBox="0 0 24 24"><path d="M5 4h14v16H5zM8 8h8M8 12h8M8 16h5" /></svg></span><div><strong>组织职责</strong><small>该组织的职责定位与协同边界</small></div></header>
            <p>{{ selectedOrg.description || '该组织暂未维护职责说明。' }}</p>
          </section>

          <section class="directory-page__drawer-section">
            <header><span class="is-blue"><svg viewBox="0 0 24 24"><path d="M16 20v-1.7a3.8 3.8 0 0 0-3.8-3.8H7.3a3.8 3.8 0 0 0-3.8 3.8V20M9.8 10.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8Z" /></svg></span><div><strong>组织负责人</strong><small>来自正式员工任职关系</small></div></header>
            <div v-if="selectedManager" class="directory-page__manager-card">
              <span>{{ initials(selectedManager.displayName) }}</span>
              <div><strong>{{ selectedManager.displayName }}</strong><small>{{ selectedManager.employeeNo }}</small></div>
              <div><small>岗位</small><strong>{{ selectedManager.positionName }}</strong></div>
            </div>
            <p v-else class="directory-page__empty-copy">暂未设置组织负责人</p>
          </section>

          <section class="directory-page__drawer-section">
            <header><span class="is-green"><svg viewBox="0 0 24 24"><path d="M16 20v-1.7a3.8 3.8 0 0 0-3.8-3.8H7.3a3.8 3.8 0 0 0-3.8 3.8V20M9.8 10.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8ZM16.5 4.2a3.4 3.4 0 0 1 0 6.4M20.5 20v-1.7a3.8 3.8 0 0 0-2.8-3.7" /></svg></span><div><strong>直属成员</strong><small>{{ selectedMembers.length }} 位有效任职成员</small></div></header>
            <div class="directory-page__member-grid">
              <article v-for="member in selectedMembers" :key="`${member.employeeId}-${member.positionId}`">
                <span>{{ initials(member.displayName) }}</span>
                <div><strong>{{ member.displayName }}</strong><small>{{ member.positionName }} · {{ member.employeeNo }}</small></div>
                <i v-if="member.employeeId === selectedOrg.managerEmployeeId">负责人</i>
              </article>
              <p v-if="!selectedMembers.length" class="directory-page__empty-copy">当前组织暂无直属成员</p>
            </div>
          </section>

          <section class="directory-page__drawer-section">
            <header><span class="is-purple"><svg viewBox="0 0 24 24"><path d="M8 4H4v4M16 4h4v4M8 20H4v-4M16 20h4v-4" /></svg></span><div><strong>下级组织</strong><small>{{ selectedChildren.length }} 个直属下级</small></div></header>
            <div class="directory-page__child-grid">
              <button v-for="child in selectedChildren" :key="child.id" type="button" @click="openOrg(child.id)">
                <span>{{ initials(child.orgName) }}</span><div><strong>{{ child.orgName }}</strong><small>{{ orgTypeLabel(child.orgType) }} · {{ child.memberCount }} 人</small></div><b>›</b>
              </button>
              <p v-if="!selectedChildren.length" class="directory-page__empty-copy">当前组织没有直属下级组织</p>
            </div>
          </section>

          <footer>
            <svg viewBox="0 0 16 16"><path d="M8 1.7a6.3 6.3 0 1 0 0 12.6A6.3 6.3 0 0 0 8 1.7Zm0 4v3.2M8 11.5h.01" /></svg>
            手机号、证件号等敏感字段不会在组织架构详情中返回。
          </footer>
        </aside>
      </div>
    </template>

    <div v-else class="directory-page__directory">
      <section class="directory-page__directory-tools">
        <label>
          <svg viewBox="0 0 16 16"><circle cx="7" cy="7" r="4.3" /><path d="m10.3 10.3 3.2 3.2" /></svg>
          <input v-model="query" type="search" placeholder="搜索姓名、工号、组织或岗位" aria-label="搜索通讯录">
        </label>
        <select v-model="directoryOrgId" aria-label="按组织筛选">
          <option value="">全部组织</option>
          <option v-for="node in data.organizations" :key="node.id" :value="node.id">{{ node.orgName }}</option>
        </select>
        <span>找到 <strong>{{ filteredMembers.length }}</strong> 位成员</span>
      </section>
      <div class="directory-page__table-wrap">
        <table>
          <thead><tr><th>员工</th><th>工号</th><th>所属组织</th><th>岗位</th></tr></thead>
          <tbody>
            <tr v-for="member in filteredMembers" :key="`${member.employeeId}-${member.positionId}`">
              <td><span class="directory-page__table-person"><i>{{ initials(member.displayName) }}</i><strong>{{ member.displayName }}</strong></span></td>
              <td>{{ member.employeeNo }}</td>
              <td>{{ member.orgName }}</td>
              <td>{{ member.positionName }}</td>
            </tr>
            <tr v-if="!filteredMembers.length"><td colspan="4" class="directory-page__table-empty">暂无匹配成员</td></tr>
          </tbody>
        </table>
      </div>
      <p class="directory-page__privacy">通讯录默认不返回手机号、证件号等敏感字段；完整联系方式需通过独立敏感字段授权能力提供。</p>
    </div>
  </section>
</template>

<style scoped>
.directory-page {
  --directory-orange: #df6830;
  --directory-border: #e4e7ed;
  display: grid;
  gap: 1rem;
  min-width: 0;
  padding: 1.15rem clamp(1rem, 2vw, 1.55rem) 1.55rem;
  background: #f5f6f8;
  color: #273248;
}
.directory-page__header-compact { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 0; border-bottom: 0; }
.directory-page__heading { display: grid; gap: .28rem; }
.directory-page__heading > p { margin: 0; color: #9aa3b2; font-size: .64rem; }
.directory-page__heading > div { display: flex; align-items: baseline; gap: .7rem; }
.directory-page__heading h1 { margin: 0; font-size: 1.38rem; letter-spacing: -.02em; }
.directory-page__heading span { color: #8d97a7; font-size: .72rem; }
.directory-page__header-actions { display: flex; align-items: center; gap: .65rem; color: #8b95a5; font-size: .66rem; }
.directory-page__header-actions > span { display: flex; align-items: center; gap: .36rem; }
.directory-page__header-actions i { width: .42rem; height: .42rem; border-radius: 50%; background: #4fa369; box-shadow: 0 0 0 3px #e8f7ed; }
.directory-page__header-actions i.is-loading { background: #e99343; box-shadow: 0 0 0 3px #fff1df; animation: directory-pulse 1s infinite; }
.directory-page button,
.directory-page input,
.directory-page select { font: inherit; }
.directory-page__header-actions button { display: inline-flex; align-items: center; gap: .35rem; min-height: 2.3rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: .52rem .72rem; background: #fff; color: #647086; font-size: .68rem; font-weight: 720; cursor: pointer; }
.directory-page__header-actions button svg { width: .82rem; height: .82rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.directory-page button:disabled { cursor: not-allowed; opacity: .48; }
.directory-page__error { margin: 0; padding: .72rem .9rem; border: 1px solid #f0c6bf; border-radius: .7rem; background: #fff1ef; color: #a53b2e; font-size: .73rem; }
.directory-page__overview { display: grid; grid-template-columns: minmax(19rem, 1.2fr) minmax(34rem, 2fr); align-items: stretch; gap: .8rem; }
.directory-page__overview-copy { display: flex; align-items: center; gap: .85rem; padding: 1rem; border: 1px solid #eadfd9; border-radius: .9rem; background: linear-gradient(135deg, #fff8f3, #fff); box-shadow: 0 5px 18px rgb(57 43 34 / 4%); }
.directory-page__overview-icon { display: grid; flex: 0 0 auto; place-items: center; width: 3.25rem; height: 3.25rem; border-radius: .9rem; background: #fff0e7; color: #d9612a; }
.directory-page__overview-icon svg { width: 1.7rem; height: 1.7rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.45; }
.directory-page__overview-copy > div { display: grid; gap: .2rem; }
.directory-page__overview-copy small { color: #d36a38; font-size: .58rem; font-weight: 800; letter-spacing: .12em; }
.directory-page__overview-copy strong { font-size: 1rem; }
.directory-page__overview-copy span { color: #8c96a6; font-size: .67rem; line-height: 1.55; }
.directory-page__overview-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); overflow: hidden; border: 1px solid var(--directory-border); border-radius: .9rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.directory-page__overview-metrics article { display: grid; grid-template-columns: auto auto; align-content: center; align-items: baseline; justify-content: center; gap: .1rem .25rem; min-width: 0; min-height: 5.2rem; padding: .7rem; border-left: 1px solid #edf0f4; text-align: center; }
.directory-page__overview-metrics article:first-child { border-left: 0; }
.directory-page__overview-metrics small { grid-column: 1 / -1; color: #8e98a7; font-size: .6rem; }
.directory-page__overview-metrics strong { font-size: 1.25rem; }
.directory-page__overview-metrics span { color: #9aa3b1; font-size: .58rem; }
.directory-page__architecture-card { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; min-height: 43rem; border: 1px solid var(--directory-border); border-radius: .92rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.directory-page__architecture-card > header { display: flex; align-items: center; justify-content: space-between; gap: .8rem; padding: .78rem .9rem; border-bottom: 1px solid #e8ebf0; }
.directory-page__architecture-card > header > div:first-child { display: grid; gap: .12rem; }
.directory-page__architecture-card > header strong { font-size: .82rem; }
.directory-page__architecture-card > header span { color: #929baa; font-size: .62rem; }
.directory-page__chart-tools { display: flex; align-items: center; gap: .38rem; }
.directory-page__chart-tools label { display: grid; grid-template-columns: .9rem minmax(8rem, 13rem) auto; align-items: center; gap: .3rem; min-height: 2rem; padding: 0 .52rem; border: 1px solid #dfe3e9; border-radius: .55rem; background: #f9fafb; }
.directory-page__chart-tools label:focus-within { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; background: #fff; }
.directory-page__chart-tools label svg { width: .78rem; height: .78rem; fill: none; stroke: #929baa; stroke-linecap: round; stroke-width: 1.4; }
.directory-page__chart-tools input { min-width: 0; border: 0; outline: 0; background: transparent; color: #3b4659; font-size: .66rem; }
.directory-page__chart-tools label button { border: 0; padding: 0; background: transparent; color: #9aa3b1; cursor: pointer; }
.directory-page__chart-tools select { min-height: 2rem; border: 1px solid #dfe3e9; border-radius: .55rem; padding: 0 .48rem; outline: 0; background: #fff; color: #657085; font-size: .65rem; }
.directory-page__chart-tools > button { display: grid; place-items: center; width: 2rem; height: 2rem; border: 1px solid #dfe3e9; border-radius: .52rem; background: #fff; color: #6b7688; font-size: .9rem; cursor: pointer; }
.directory-page__chart-tools > span { min-width: 2.7rem; text-align: center; }
.directory-page__chart-stage { overflow: auto; min-height: 36rem; background: #f8f9fb; }
.directory-page__chart-scale { min-width: max-content; transform: scale(var(--chart-scale)); transform-origin: top left; transition: transform .18s ease; }
.directory-page__architecture-card > footer { display: flex; align-items: center; flex-wrap: wrap; gap: .8rem; min-height: 2.8rem; padding: .62rem .9rem; border-top: 1px solid #e8ebf0; background: #fff; color: #7f8999; font-size: .62rem; }
.directory-page__architecture-card > footer > span { display: inline-flex; align-items: center; gap: .28rem; }
.directory-page__architecture-card > footer i { width: .52rem; height: .52rem; border-radius: .16rem; background: #edf2fa; }
.directory-page__architecture-card > footer i.is-company { background: #fff0e8; }
.directory-page__architecture-card > footer i.is-center { background: #e8f7ff; }
.directory-page__architecture-card > footer i.is-department { background: #e9f8f0; }
.directory-page__architecture-card > footer i.is-group { background: #fff7dc; }
.directory-page__architecture-card > footer em { margin-left: auto; color: #9aa3b1; font-style: normal; }
.directory-page__drawer-backdrop { position: fixed; z-index: 70; inset: 0; display: flex; justify-content: flex-end; background: rgb(23 31 45 / 28%); backdrop-filter: blur(2px); }
.directory-page__drawer { display: grid; grid-template-rows: auto auto auto auto; align-content: start; gap: .8rem; overflow: auto; width: min(34rem, 94vw); height: 100%; padding: .95rem 1rem 1.2rem; background: #f7f8fa; box-shadow: -18px 0 50px rgb(23 31 45 / 18%); animation: directory-drawer-in .22s ease both; scrollbar-color: #ccd2dc transparent; scrollbar-width: thin; }
.directory-page__drawer > header { position: sticky; z-index: 4; top: -.95rem; display: flex; align-items: center; justify-content: space-between; gap: .5rem; min-height: 2.8rem; margin: -.95rem -1rem 0; padding: .7rem 1rem; border-bottom: 1px solid #e6e9ee; background: rgb(255 255 255 / 96%); backdrop-filter: blur(10px); }
.directory-page__drawer-crumbs { display: flex; align-items: center; overflow: hidden; gap: .25rem; min-width: 0; color: #a0a8b5; font-size: .61rem; }
.directory-page__drawer-crumbs button { overflow: hidden; border: 0; padding: 0; background: transparent; color: #7d8798; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.directory-page__drawer-crumbs button:hover { color: var(--directory-orange); }
.directory-page__drawer-close { display: grid; flex: 0 0 auto; place-items: center; width: 1.9rem; height: 1.9rem; border: 1px solid #dfe3e9; border-radius: 50%; background: #fff; color: #7c8798; font-size: 1rem; cursor: pointer; }
.directory-page__drawer-identity { display: flex; align-items: center; gap: .8rem; padding: .95rem; border: 1px solid #eadfd9; border-radius: .88rem; background: linear-gradient(135deg, #fff8f3, #fff); }
.directory-page__drawer-identity > span { display: grid; flex: 0 0 auto; place-items: center; width: 3.6rem; height: 3.6rem; border-radius: .95rem; background: #fff0e8; color: #d75f29; font-size: .85rem; font-weight: 800; }
.directory-page__drawer-identity > span[data-type="CENTER"] { background: #e8f7ff; color: #267ea7; }
.directory-page__drawer-identity > span[data-type="DEPARTMENT"] { background: #e9f8f0; color: #338260; }
.directory-page__drawer-identity > span[data-type="GROUP"] { background: #fff7dc; color: #93711e; }
.directory-page__drawer-identity > div { display: grid; gap: .12rem; min-width: 0; }
.directory-page__drawer-identity p { display: flex; align-items: center; gap: .35rem; margin: 0; color: #8e98a8; font-size: .63rem; }
.directory-page__drawer-identity p i { padding: .14rem .35rem; border-radius: 999px; background: #f1f2f5; color: #8d96a4; font-style: normal; font-weight: 700; }
.directory-page__drawer-identity p i.is-on { background: #e9f8ef; color: #3c8657; }
.directory-page__drawer-identity h2 { overflow: hidden; margin: 0; font-size: 1.25rem; text-overflow: ellipsis; white-space: nowrap; }
.directory-page__drawer-identity small { color: #969fad; font-size: .67rem; }
.directory-page__drawer-metrics { display: grid; grid-template-columns: repeat(4, 1fr); overflow: hidden; border: 1px solid var(--directory-border); border-radius: .82rem; background: #fff; }
.directory-page__drawer-metrics article { display: grid; grid-template-columns: auto auto; align-items: baseline; justify-content: center; gap: .05rem .2rem; min-height: 4.25rem; padding: .58rem; border-left: 1px solid #edf0f4; }
.directory-page__drawer-metrics article:first-child { border-left: 0; }
.directory-page__drawer-metrics small { grid-column: 1 / -1; color: #919baa; font-size: .58rem; }
.directory-page__drawer-metrics strong { font-size: 1rem; }
.directory-page__drawer-metrics span { color: #9ca4b1; font-size: .55rem; }
.directory-page__drawer-section { display: grid; gap: .72rem; padding: .88rem; border: 1px solid var(--directory-border); border-radius: .84rem; background: #fff; }
.directory-page__drawer-section > header { display: flex; align-items: center; gap: .55rem; }
.directory-page__drawer-section > header > span { display: grid; flex: 0 0 auto; place-items: center; width: 1.9rem; height: 1.9rem; border-radius: .55rem; background: #fff0e8; color: #d7612b; }
.directory-page__drawer-section > header > span.is-blue { background: #eaf3ff; color: #4879b5; }
.directory-page__drawer-section > header > span.is-green { background: #eaf8ef; color: #3e8959; }
.directory-page__drawer-section > header > span.is-purple { background: #f3edff; color: #7d5aad; }
.directory-page__drawer-section > header svg { width: 1.05rem; height: 1.05rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.directory-page__drawer-section > header > div { display: grid; gap: .06rem; }
.directory-page__drawer-section > header strong { font-size: .78rem; }
.directory-page__drawer-section > header small { color: #969fad; font-size: .59rem; }
.directory-page__drawer-section > p { margin: 0; color: #6f7a8d; font-size: .7rem; line-height: 1.75; }
.directory-page__manager-card { display: grid; grid-template-columns: 2.35rem minmax(0, 1fr) minmax(6rem, .8fr); align-items: center; gap: .62rem; padding: .65rem; border-radius: .68rem; background: #f8f9fb; }
.directory-page__manager-card > span { display: grid; place-items: center; width: 2.35rem; height: 2.35rem; border-radius: .65rem; background: #eaf3ff; color: #5176a5; font-size: .66rem; font-weight: 800; }
.directory-page__manager-card > div { display: grid; gap: .08rem; min-width: 0; }
.directory-page__manager-card strong { overflow: hidden; font-size: .7rem; text-overflow: ellipsis; white-space: nowrap; }
.directory-page__manager-card small { overflow: hidden; color: #929cab; font-size: .58rem; text-overflow: ellipsis; white-space: nowrap; }
.directory-page__member-grid,
.directory-page__child-grid { display: grid; gap: .4rem; }
.directory-page__member-grid article,
.directory-page__child-grid button { display: grid; grid-template-columns: 2.15rem minmax(0, 1fr) auto; align-items: center; gap: .55rem; min-height: 3.15rem; padding: .5rem .58rem; border: 1px solid #e8ebf0; border-radius: .65rem; background: #fff; text-align: left; }
.directory-page__member-grid article > span,
.directory-page__child-grid button > span { display: grid; place-items: center; width: 2.15rem; height: 2.15rem; border-radius: .6rem; background: #edf3fc; color: #5577a5; font-size: .61rem; font-weight: 800; }
.directory-page__child-grid button > span { background: #eaf8f0; color: #378465; }
.directory-page__member-grid article > div,
.directory-page__child-grid button > div { display: grid; gap: .08rem; min-width: 0; }
.directory-page__member-grid strong,
.directory-page__child-grid strong { overflow: hidden; color: #3a4558; font-size: .69rem; text-overflow: ellipsis; white-space: nowrap; }
.directory-page__member-grid small,
.directory-page__child-grid small { overflow: hidden; color: #929baa; font-size: .57rem; text-overflow: ellipsis; white-space: nowrap; }
.directory-page__member-grid i { padding: .16rem .34rem; border-radius: 999px; background: #fff0e8; color: #ce5b28; font-size: .54rem; font-style: normal; font-weight: 750; }
.directory-page__child-grid button { cursor: pointer; }
.directory-page__child-grid button:hover { border-color: #edb89f; background: #fffaf7; }
.directory-page__child-grid b { color: #a5adba; font-size: 1rem; }
.directory-page__empty-copy { margin: 0; padding: .75rem; border-radius: .62rem; background: #f8f9fb; color: #9aa3b1; font-size: .65rem; text-align: center; }
.directory-page__drawer > footer { display: flex; align-items: center; gap: .35rem; padding: .68rem .75rem; border-radius: .65rem; background: #eef4fb; color: #64758e; font-size: .62rem; }
.directory-page__drawer > footer svg { flex: 0 0 auto; width: .85rem; height: .85rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.35; }
.directory-page__directory { display: grid; gap: .75rem; }
.directory-page__directory-tools { display: grid; grid-template-columns: minmax(15rem, 1fr) minmax(12rem, 18rem) auto; align-items: center; gap: .55rem; padding: .8rem; border: 1px solid var(--directory-border); border-radius: .82rem; background: #fff; }
.directory-page__directory-tools label { display: grid; grid-template-columns: 1rem minmax(0, 1fr); align-items: center; gap: .35rem; min-height: 2.35rem; padding: 0 .65rem; border: 1px solid #dfe3e9; border-radius: .58rem; background: #f9fafb; }
.directory-page__directory-tools label:focus-within { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; background: #fff; }
.directory-page__directory-tools label svg { width: .85rem; height: .85rem; fill: none; stroke: #929baa; stroke-linecap: round; stroke-width: 1.4; }
.directory-page__directory-tools input { border: 0; outline: 0; background: transparent; color: #3b4659; font-size: .72rem; }
.directory-page__directory-tools select { min-height: 2.35rem; border: 1px solid #dfe3e9; border-radius: .58rem; padding: 0 .65rem; outline: 0; background: #fff; color: #657085; font-size: .7rem; }
.directory-page__directory-tools > span { color: #8c96a6; font-size: .66rem; white-space: nowrap; }
.directory-page__directory-tools > span strong { color: #cf5c29; }
.directory-page__table-wrap { overflow: auto; border: 1px solid var(--directory-border); border-radius: .88rem; background: #fff; box-shadow: 0 5px 18px rgb(35 48 69 / 4%); }
.directory-page table { width: 100%; border-collapse: collapse; }
.directory-page th,
.directory-page td { padding: .76rem .9rem; border-bottom: 1px solid #edf0f4; color: #5f6b7f; font-size: .7rem; text-align: left; white-space: nowrap; }
.directory-page th { background: #fafbfc; color: #8892a2; font-size: .62rem; font-weight: 700; }
.directory-page__table-person { display: flex; align-items: center; gap: .55rem; }
.directory-page__table-person i { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: .55rem; background: #edf3fc; color: #5577a5; font-size: .57rem; font-style: normal; font-weight: 800; }
.directory-page__table-person strong { color: #354054; font-size: .72rem; }
.directory-page__table-empty { height: 12rem; color: #9aa3b1 !important; text-align: center !important; }
.directory-page__privacy { margin: 0; color: #8f99a8; font-size: .62rem; }
@keyframes directory-pulse { 50% { opacity: .42; transform: scale(.78); } }
@keyframes directory-drawer-in { from { opacity: 0; transform: translateX(2rem); } }
@media (max-width: 1180px) {
  .directory-page__overview { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .directory-page__header-compact { align-items: flex-start; flex-direction: column; }
  .directory-page__header-actions { width: 100%; justify-content: space-between; }
  .directory-page__architecture-card > header { align-items: flex-start; flex-direction: column; }
  .directory-page__chart-tools { width: 100%; flex-wrap: wrap; }
  .directory-page__chart-tools label { flex: 1; }
  .directory-page__directory-tools { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .directory-page { padding: .8rem; }
  .directory-page__heading > div { align-items: flex-start; flex-direction: column; gap: .2rem; }
  .directory-page__overview-metrics { grid-template-columns: repeat(2, 1fr); }
  .directory-page__overview-metrics article { border-top: 1px solid #edf0f4; }
  .directory-page__overview-metrics article:nth-child(odd) { border-left: 0; }
  .directory-page__overview-metrics article:first-child,
  .directory-page__overview-metrics article:nth-child(2) { border-top: 0; }
  .directory-page__overview-metrics article:last-child { grid-column: 1 / -1; }
  .directory-page__chart-tools label { flex-basis: 100%; }
  .directory-page__architecture-card > footer em { width: 100%; margin-left: 0; }
  .directory-page__drawer-metrics { grid-template-columns: repeat(2, 1fr); }
  .directory-page__drawer-metrics article:nth-child(3) { border-left: 0; border-top: 1px solid #edf0f4; }
  .directory-page__drawer-metrics article:nth-child(4) { border-top: 1px solid #edf0f4; }
}
</style>
