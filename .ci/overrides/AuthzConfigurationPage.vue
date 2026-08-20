<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type {
  AuthzCapabilityType,
  AuthzModuleView,
  AuthzOrgModuleView,
  AuthzPermissionView,
  AuthzPositionRoleView,
  AuthzPreviewResult,
  AuthzReferenceData,
} from '../../../contracts'
import { createAuthzApi } from '../../../services/authz/authz-api'
import { usePortalSessionStore } from '../../../session'

type Mode = 'modules' | 'module-permissions' | 'org-modules' | 'position-roles' | 'preview'

const props = defineProps<{ mode: Mode }>()
const route = useRoute()
const session = usePortalSessionStore()
const api = createAuthzApi(session)

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const assertion = ref('')
const modules = ref<AuthzModuleView[]>([])
const permissions = ref<AuthzPermissionView[]>([])
const orgModules = ref<AuthzOrgModuleView[]>([])
const positionRoles = ref<AuthzPositionRoleView[]>([])
const preview = ref<AuthzPreviewResult | null>(null)
const orgId = ref(String(route.params.orgId ?? ''))
const positionId = ref(String(route.params.positionId ?? ''))
const directRoles = ref<string[]>([])
const reference = ref<AuthzReferenceData>({
  organizations: [],
  positions: [],
  roles: [],
  permissions: [],
})

const moduleId = computed(() => String(route.params.id ?? ''))
const selectedModule = computed(() => (
  modules.value.find((module) => module.id === moduleId.value)
))
const title = computed(() => ({
  modules: '模块目录',
  'module-permissions': '模块权限编排',
  'org-modules': '组织模块配置',
  'position-roles': '岗位角色配置',
  preview: '多角色权限模拟',
} as const)[props.mode])
const filteredPositions = computed(() => (
  reference.value.positions.filter((position) => !orgId.value || position.orgId === orgId.value)
))

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : '请求失败'
}

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

async function run<T>(operation: () => Promise<T>, successMessage?: string): Promise<T | undefined> {
  clearFeedback()
  try {
    const result = await operation()
    if (successMessage) notice.value = successMessage
    return result
  } catch (cause) {
    error.value = errorMessage(cause)
    return undefined
  }
}

async function issueStepUpTicket(): Promise<string> {
  const value = assertion.value.trim()
  if (!value) throw new Error('高风险写操作需要先输入二次认证断言')
  const response = await api.issueStepUp(value)
  return response.ticket
}

function ensureOrgSelection(): void {
  orgId.value ||= reference.value.organizations[0]?.id ?? ''
}

function ensurePositionSelection(): void {
  const firstMatchingPosition = reference.value.positions
    .find((position) => position.orgId === orgId.value)
  positionId.value ||= firstMatchingPosition?.id ?? reference.value.positions[0]?.id ?? ''
}

async function loadModulePermissions(): Promise<void> {
  if (!moduleId.value) {
    permissions.value = []
    return
  }
  permissions.value = await run(() => api.modulePermissions(moduleId.value)) ?? []
}

async function loadOrgModuleSelections(): Promise<void> {
  ensureOrgSelection()
  if (!orgId.value) {
    orgModules.value = []
    return
  }
  orgModules.value = await run(() => api.orgModules(orgId.value)) ?? []
}

async function loadPositionRoleSelections(): Promise<void> {
  ensurePositionSelection()
  if (!positionId.value) {
    positionRoles.value = []
    return
  }
  positionRoles.value = await run(() => api.positionRoles(positionId.value)) ?? []
}

function preparePreviewSelections(): void {
  ensureOrgSelection()
  ensurePositionSelection()
}

async function loadModeData(): Promise<void> {
  switch (props.mode) {
    case 'module-permissions':
      await loadModulePermissions()
      break
    case 'org-modules':
      await loadOrgModuleSelections()
      break
    case 'position-roles':
      await loadPositionRoleSelections()
      break
    case 'preview':
      preparePreviewSelections()
      break
    default:
      break
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const base = await run(() => Promise.all([api.modules(), api.referenceData()]))
    if (!base) return
    ;[modules.value, reference.value] = base
    await loadModeData()
  } finally {
    loading.value = false
  }
}

async function withSaving(operation: () => Promise<void>): Promise<void> {
  saving.value = true
  try {
    await operation()
  } finally {
    saving.value = false
  }
}

async function saveModulePermissions(): Promise<void> {
  await withSaving(async () => {
    const ticket = await issueStepUpTicket()
    const selections = permissions.value
      .filter((permission) => permission.capabilityType)
      .map((permission) => ({
        permissionId: permission.id,
        capabilityType: permission.capabilityType as AuthzCapabilityType,
      }))
    permissions.value = await run(
      () => api.replaceModulePermissions(moduleId.value, selections, ticket),
      '模块权限已保存',
    ) ?? permissions.value
  })
}

async function saveOrgModules(): Promise<void> {
  await withSaving(async () => {
    const ticket = await issueStepUpTicket()
    const selections = orgModules.value.map((module) => ({
      moduleId: module.moduleId,
      enabled: module.enabled,
      inheritToChildren: module.inheritToChildren,
      effectiveStartAt: module.effectiveStartAt,
      effectiveEndAt: module.effectiveEndAt,
      remark: module.remark,
    }))
    orgModules.value = await run(
      () => api.replaceOrgModules(orgId.value, selections, ticket),
      '组织模块配置已保存',
    ) ?? orgModules.value
  })
}

async function savePositionRoles(): Promise<void> {
  await withSaving(async () => {
    const ticket = await issueStepUpTicket()
    const selections = positionRoles.value
      .filter((role) => role.selected)
      .map((role) => ({
        roleId: role.roleId,
        effectiveStartAt: role.effectiveStartAt,
        effectiveEndAt: role.effectiveEndAt,
        grantSource: role.grantSource ?? 'POSITION_CONFIG',
      }))
    positionRoles.value = await run(
      () => api.replacePositionRoles(positionId.value, selections, ticket),
      '岗位角色已保存',
    ) ?? positionRoles.value
  })
}

async function simulate(): Promise<void> {
  preview.value = await run(() => api.preview({
    orgId: orgId.value,
    positionId: positionId.value,
    directRoleIds: directRoles.value,
    effectiveAt: null,
  })) ?? null
}

function handleModeChanged(): void {
  void load()
}

function handleOrgChanged(): void {
  if (props.mode === 'org-modules' && orgId.value) void loadOrgModuleSelections()
  if (props.mode === 'preview') ensurePositionSelection()
}

function handlePositionChanged(): void {
  if (props.mode === 'position-roles' && positionId.value) void loadPositionRoleSelections()
}

watch(() => props.mode, handleModeChanged)
watch(orgId, handleOrgChanged)
watch(positionId, handlePositionChanged)
onMounted(handleModeChanged)
</script>

<template>
  <section class="authz-configuration">
    <header class="authz-configuration__header">
      <div>
        <p class="authz-configuration__eyebrow">技术端 · 权限配置</p>
        <h2>{{ title }}</h2>
        <span>模块层只收窄既有 RBAC 权限；配置预览不会写入正式授权表。</span>
      </div>
      <div class="authz-configuration__kpis">
        <b>{{ modules.length }}</b><small>模块</small>
        <b>{{ reference.roles.length }}</b><small>角色</small>
      </div>
    </header>

    <p v-if="error" class="authz-alert authz-alert--error">{{ error }}</p>
    <p v-if="notice" class="authz-alert authz-alert--success">{{ notice }}</p>
    <p v-if="loading" class="authz-empty">正在加载真实权限配置…</p>

    <template v-else>
      <div v-if="mode === 'modules'" class="authz-grid">
        <article v-for="module in modules" :key="module.id">
          <div class="authz-row">
            <strong>{{ module.moduleName }}</strong>
            <i :class="{ 'is-enabled': module.enabled }">{{ module.enabled ? '启用' : '停用' }}</i>
          </div>
          <code>{{ module.moduleCode }}</code>
          <p>{{ module.moduleGroup || '未分组' }} · {{ module.permissionCount }} 个权限点</p>
          <RouterLink :to="`/tech/authz/modules/${module.id}`">配置权限 →</RouterLink>
        </article>
      </div>

      <div v-if="mode === 'module-permissions'" class="authz-panel">
        <h3>{{ selectedModule?.moduleName || '模块' }} · 权限档位</h3>
        <div class="authz-permission-list">
          <label v-for="permission in permissions" :key="permission.id">
            <span>
              <b>{{ permission.permissionName }}</b>
              <code>{{ permission.permissionCode }}</code>
            </span>
            <select v-model="permission.capabilityType">
              <option :value="null">不归属</option>
              <option>VIEW</option>
              <option>OPERATE</option>
              <option>APPROVE</option>
              <option>ADMIN</option>
            </select>
            <em>{{ permission.riskLevel }}</em>
          </label>
        </div>
        <div class="authz-write-actions">
          <input v-model="assertion" placeholder="二次认证断言 / TOTP">
          <button :disabled="saving" @click="saveModulePermissions">保存模块权限</button>
        </div>
      </div>

      <div v-if="mode === 'org-modules'" class="authz-panel">
        <div class="authz-toolbar">
          <select v-model="orgId">
            <option v-for="organization in reference.organizations" :key="organization.id" :value="organization.id">
              {{ organization.orgName }}
            </option>
          </select>
        </div>
        <div class="authz-grid">
          <article v-for="module in orgModules" :key="module.moduleId">
            <label class="authz-switch">
              <input v-model="module.enabled" type="checkbox">
              <strong>{{ module.moduleName }}</strong>
            </label>
            <label class="authz-switch">
              <input v-model="module.inheritToChildren" type="checkbox">
              继承到下级组织
            </label>
            <code>{{ module.moduleCode }}</code>
          </article>
        </div>
        <div class="authz-write-actions">
          <input v-model="assertion" placeholder="二次认证断言 / TOTP">
          <button :disabled="saving" @click="saveOrgModules">保存组织配置</button>
        </div>
      </div>

      <div v-if="mode === 'position-roles'" class="authz-panel">
        <div class="authz-toolbar">
          <select v-model="positionId">
            <option v-for="position in reference.positions" :key="position.id" :value="position.id">
              {{ position.positionName }}
            </option>
          </select>
        </div>
        <div class="authz-grid">
          <label v-for="role in positionRoles" :key="role.roleId" class="authz-role">
            <input v-model="role.selected" type="checkbox">
            <span>
              <b>{{ role.roleName }}</b>
              <code>{{ role.roleCode }}</code>
              <small>数据范围 {{ role.dataScopeCode || '未设置' }}</small>
            </span>
          </label>
        </div>
        <div class="authz-write-actions">
          <input v-model="assertion" placeholder="二次认证断言 / TOTP">
          <button :disabled="saving" @click="savePositionRoles">保存岗位角色</button>
        </div>
      </div>

      <div v-if="mode === 'preview'" class="authz-panel">
        <div class="authz-preview-form">
          <label>
            组织
            <select v-model="orgId">
              <option v-for="organization in reference.organizations" :key="organization.id" :value="organization.id">
                {{ organization.orgName }}
              </option>
            </select>
          </label>
          <label>
            岗位
            <select v-model="positionId">
              <option v-for="position in filteredPositions" :key="position.id" :value="position.id">
                {{ position.positionName }}
              </option>
            </select>
          </label>
        </div>
        <h3>额外模拟直接角色</h3>
        <div class="authz-chips">
          <label v-for="role in reference.roles" :key="role.id">
            <input v-model="directRoles" type="checkbox" :value="role.id">
            {{ role.roleName }}
          </label>
        </div>
        <button class="authz-primary" @click="simulate">运行多角色模拟</button>
        <div v-if="preview" class="authz-result">
          <h3>
            有效权限 {{ preview.effectivePermissionCodes.length }} ·
            被模块过滤 {{ preview.filteredPermissionCodes.length }}
          </h3>
          <div class="authz-result__permissions">
            <article
              v-for="permission in preview.permissions"
              :key="permission.permissionCode"
              :class="{ 'is-blocked': !permission.effective }"
            >
              <b>{{ permission.permissionName }}</b>
              <code>{{ permission.permissionCode }}</code>
              <small>{{ permission.reason }}</small>
            </article>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.authz-configuration { display: grid; gap: 14px; color: #0f172a; }
.authz-configuration__header,
.authz-panel,
.authz-grid article { background: #fff; border: 1px solid rgba(15, 23, 42, .08); border-radius: 16px; }
.authz-configuration__header { display: flex; justify-content: space-between; gap: 20px; padding: 20px; }
.authz-configuration__eyebrow { margin: 0; color: #ea580c; font-size: 11px; font-weight: 800; letter-spacing: .12em; }
.authz-configuration h2 { margin: 5px 0; font-size: 24px; }
.authz-configuration__header span,
.authz-configuration small { color: #64748b; }
.authz-configuration__kpis { display: grid; grid-template-columns: auto auto; align-content: center; gap: 3px 8px; }
.authz-configuration__kpis b { color: #ea580c; font-size: 22px; }
.authz-configuration__kpis small { font-size: 11px; }
.authz-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 12px; }
.authz-grid article { padding: 15px; }
.authz-row,
.authz-toolbar,
.authz-write-actions,
.authz-preview-form { display: flex; align-items: center; gap: 10px; }
.authz-row { justify-content: space-between; }
.authz-row i { padding: 3px 8px; border-radius: 999px; color: #64748b; background: #f1f5f9; font-size: 11px; font-style: normal; }
.authz-row i.is-enabled { color: #047857; background: #ecfdf5; }
.authz-configuration code { display: block; margin-top: 5px; color: #9a3412; font-size: 11px; }
.authz-configuration article p { color: #64748b; font-size: 12px; }
.authz-configuration a { color: #ea580c; font-weight: 700; text-decoration: none; }
.authz-panel { padding: 18px; }
.authz-panel h3 { margin: 0 0 12px; }
.authz-permission-list { display: grid; max-height: 520px; overflow: auto; border: 1px solid #e2e8f0; border-radius: 12px; }
.authz-permission-list > label { display: grid; grid-template-columns: 1fr 150px 70px; gap: 12px; align-items: center; padding: 10px 12px; border-bottom: 1px solid #eef2f7; }
.authz-permission-list b,
.authz-permission-list code { display: block; }
.authz-permission-list code { margin: 2px 0 0; }
.authz-permission-list em { color: #64748b; font-size: 11px; font-style: normal; }
.authz-configuration select,
.authz-configuration input { min-height: 40px; padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 10px; background: #fff; font: inherit; }
.authz-write-actions { justify-content: flex-end; margin-top: 14px; }
.authz-write-actions input { min-width: 280px; }
.authz-configuration button { min-height: 40px; padding: 0 16px; border: 0; border-radius: 10px; color: #fff; background: #ea580c; font-weight: 700; cursor: pointer; }
.authz-switch,
.authz-role,
.authz-chips label { display: flex; gap: 8px; align-items: center; }
.authz-switch { margin: 8px 0; }
.authz-role { padding: 12px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fff; }
.authz-role input,
.authz-switch input,
.authz-chips input { min-height: auto; }
.authz-role span { display: grid; gap: 2px; }
.authz-preview-form { flex-wrap: wrap; }
.authz-preview-form label { display: grid; gap: 5px; min-width: 260px; }
.authz-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.authz-chips label { padding: 7px 10px; border: 1px solid #e2e8f0; border-radius: 999px; font-size: 12px; }
.authz-primary { margin-top: 14px; }
.authz-result { margin-top: 18px; }
.authz-result__permissions { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; }
.authz-result__permissions article { padding: 12px; border-left: 3px solid #059669; }
.authz-result__permissions article.is-blocked { border-left-color: #e11d48; background: #fff7f8; }
.authz-result__permissions small { display: block; margin-top: 5px; }
.authz-alert { padding: 10px 13px; border-radius: 10px; }
.authz-alert--error { color: #be123c; background: #fff1f2; }
.authz-alert--success { color: #047857; background: #ecfdf5; }
.authz-empty { padding: 40px; color: #64748b; text-align: center; }
@media (max-width: 720px) {
  .authz-configuration__header { flex-direction: column; }
  .authz-permission-list > label { grid-template-columns: 1fr; }
  .authz-write-actions { align-items: stretch; flex-direction: column; }
  .authz-write-actions input { min-width: 0; }
}
</style>
