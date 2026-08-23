<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { OrgArchitectureDirectory } from '../../../contracts'
import { createOrgArchitectureApi } from '../../../services/org-architecture/org-architecture-api'
import { usePortalSessionStore } from '../../../session'
import OrgArchitectureTree from './OrgArchitectureTree.vue'
import { architectureError } from './org-architecture-view-model'

defineProps<{ mode: 'architecture' | 'directory' }>()
const session = usePortalSessionStore()
const api = createOrgArchitectureApi(session)
const data = ref<OrgArchitectureDirectory>({ versionNo: 0, publishedAt: null, organizations: [], members: [] })
const selectedOrgId = ref('')
const query = ref('')
const loading = ref(false)
const error = ref('')

const selectedOrg = computed(() => data.value.organizations.find((node) => node.id === selectedOrgId.value) ?? null)
const filteredMembers = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  return data.value.members.filter((member) => {
    if (selectedOrgId.value && member.orgId !== selectedOrgId.value) return false
    if (!keyword) return true
    return [member.displayName, member.employeeNo, member.orgName, member.positionName]
      .some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword))
  })
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.directory()
    selectedOrgId.value ||= data.value.organizations[0]?.id ?? ''
  } catch (cause) {
    error.value = architectureError(cause)
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <section class="directory-page" :aria-busy="loading">
    <div class="directory-page__header-compact">
      <h1>{{ mode === 'architecture' ? '企业架构' : '企业通讯录' }}</h1>
      <div class="directory-page__header-actions">
        <span>{{ data.organizations.length }} 个组织 · {{ data.members.length }} 位在岗成员</span>
        <button type="button" :disabled="loading" @click="load">刷新数据</button>
      </div>
    </div>

    <p v-if="error" class="directory-page__error" role="alert">{{ error }}</p>

    <div v-if="mode === 'architecture'" class="directory-page__architecture">
      <aside>
        <OrgArchitectureTree
          :nodes="data.organizations" :selected-id="selectedOrgId"
          @select="selectedOrgId = $event"
        />
      </aside>
      <main>
        <div v-if="selectedOrg" class="directory-page__detail">
          <small>{{ selectedOrg.orgCode }}</small>
          <h2>{{ selectedOrg.orgName }}</h2>
          <p>{{ selectedOrg.description || '该组织暂未维护职责说明。' }}</p>
          <dl>
            <div><dt>状态</dt><dd>{{ selectedOrg.status === 'ACTIVE' ? '启用' : '停用' }}</dd></div>
            <div><dt>在岗人数</dt><dd>{{ selectedOrg.memberCount }}</dd></div>
            <div><dt>规划编制</dt><dd>{{ selectedOrg.headcountPlan }}</dd></div>
          </dl>
        </div>
      </main>
    </div>

    <div v-else class="directory-page__directory">
      <div class="directory-page__filters">
        <input v-model="query" type="search" placeholder="搜索姓名、工号、组织或岗位" aria-label="搜索通讯录">
        <select v-model="selectedOrgId" aria-label="按组织筛选">
          <option value="">全部组织</option>
          <option v-for="node in data.organizations" :key="node.id" :value="node.id">{{ node.orgName }}</option>
        </select>
        <span>{{ filteredMembers.length }} 人</span>
      </div>
      <div class="directory-page__table-wrap">
        <table>
          <thead><tr><th>姓名</th><th>工号</th><th>组织</th><th>岗位</th></tr></thead>
          <tbody>
            <tr v-for="member in filteredMembers" :key="`${member.employeeId}-${member.positionId}`">
              <td><strong>{{ member.displayName }}</strong></td>
              <td>{{ member.employeeNo }}</td>
              <td>{{ member.orgName }}</td>
              <td>{{ member.positionName }}</td>
            </tr>
            <tr v-if="!filteredMembers.length"><td colspan="4">暂无匹配成员</td></tr>
          </tbody>
        </table>
      </div>
      <p class="directory-page__privacy">通讯录默认不返回手机号、证件号等敏感字段；全文联系方式需通过独立敏感字段授权能力提供。</p>
    </div>
  </section>
</template>

<style scoped>
.directory-page { display: grid; gap: 1rem; padding: clamp(1rem, 2vw, 1.7rem); }
.directory-page__header-compact { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding-bottom: .8rem; border-bottom: 1px solid var(--color-border, #e8ddd3); }
.directory-page__header-compact h1 { margin: 0; font-size: clamp(1.35rem, 2.2vw, 1.8rem); }
.directory-page__header-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: .75rem; color: var(--color-text-muted, #776b62); font-size: .82rem; }
.directory-page button { border: 1px solid var(--color-border, #e8ddd3); border-radius: .7rem; padding: .65rem .9rem; background: #fff; font: inherit; font-weight: 700; cursor: pointer; }
.directory-page__architecture { display: grid; grid-template-columns: minmax(20rem, 45%) 1fr; gap: 1rem; align-items: start; }
.directory-page__architecture > * { min-width: 0; border: 1px solid var(--color-border, #e8ddd3); border-radius: 1rem; background: #fff; padding: 1rem; }
.directory-page__architecture aside { max-height: 70vh; overflow: auto; }
.directory-page__detail small { color: var(--color-primary, #d76b2f); font-weight: 800; }
.directory-page__detail h2 { margin: .25rem 0 .7rem; }
.directory-page__detail p { color: var(--color-text-muted, #776b62); line-height: 1.7; }
.directory-page__detail dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: .6rem; }
.directory-page__detail dl div { padding: .8rem; border-radius: .8rem; background: #f9f5f1; }
.directory-page__detail dt { color: var(--color-text-muted, #776b62); font-size: .75rem; }
.directory-page__detail dd { margin: .25rem 0 0; font-size: 1.1rem; font-weight: 800; }
.directory-page__directory { display: grid; gap: .8rem; }
.directory-page__filters { display: grid; grid-template-columns: minmax(14rem, 1fr) minmax(12rem, 20rem) auto; gap: .6rem; align-items: center; }
.directory-page__filters input, .directory-page__filters select { border: 1px solid var(--color-border, #e8ddd3); border-radius: .7rem; padding: .7rem .8rem; font: inherit; background: #fff; }
.directory-page__table-wrap { overflow: auto; border: 1px solid var(--color-border, #e8ddd3); border-radius: 1rem; background: #fff; }
.directory-page table { width: 100%; border-collapse: collapse; }
.directory-page th, .directory-page td { padding: .8rem 1rem; border-bottom: 1px solid var(--color-border, #e8ddd3); text-align: left; white-space: nowrap; }
.directory-page th { background: #faf7f4; color: var(--color-text-muted, #776b62); font-size: .76rem; }
.directory-page__privacy { margin: 0; color: var(--color-text-muted, #776b62); font-size: .78rem; }
.directory-page__error { margin: 0; padding: .75rem 1rem; border-radius: .75rem; background: #fff0ee; color: #8f2d24; }
@media (max-width: 820px) { .directory-page__architecture { grid-template-columns: 1fr; } .directory-page__filters { grid-template-columns: 1fr; } .directory-page__header-compact { align-items: flex-start; flex-direction: column; } .directory-page__header-actions { justify-content: flex-start; } }
</style>
