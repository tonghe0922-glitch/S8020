<script setup lang="ts">
import type { OrgArchitectureNode, OrgArchitectureNodeCommand } from '../../../contracts'
import { eventValue } from '../../../design-system/eventValue'

const props = withDefaults(defineProps<{
  modelValue: OrgArchitectureNodeCommand
  nodes: OrgArchitectureNode[]
  editingId?: string
  title: string
  submitLabel: string
  disabled?: boolean
}>(), {
  editingId: '',
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: OrgArchitectureNodeCommand]
  submit: []
  cancel: []
}>()

function update<K extends keyof OrgArchitectureNodeCommand>(key: K, value: OrgArchitectureNodeCommand[K]): void {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

function integer(event: Event): number {
  const parsed = Number.parseInt(eventValue(event), 10)
  return Number.isFinite(parsed) ? parsed : 0
}
</script>

<template>
  <form class="org-editor" @submit.prevent="emit('submit')">
    <header>
      <div>
        <p class="org-editor__eyebrow">组织节点</p>
        <h2>{{ title }}</h2>
      </div>
      <button type="button" class="org-editor__ghost" @click="emit('cancel')">取消</button>
    </header>

    <div class="org-editor__grid">
      <label>
        <span>组织编码</span>
        <input
          required maxlength="64" :disabled="disabled" :value="modelValue.orgCode"
          @input="update('orgCode', eventValue($event))"
        >
      </label>
      <label>
        <span>组织名称</span>
        <input
          required maxlength="128" :disabled="disabled" :value="modelValue.orgName"
          @input="update('orgName', eventValue($event))"
        >
      </label>
      <label>
        <span>组织类型</span>
        <select :disabled="disabled" :value="modelValue.orgType" @change="update('orgType', eventValue($event))">
          <option value="MANAGEMENT">管理</option>
          <option value="DIRECT">直管</option>
          <option value="COMPANY">公司</option>
          <option value="CENTER">中心</option>
          <option value="DEPARTMENT">部门</option>
          <option value="GROUP">组</option>
        </select>
      </label>
      <label>
        <span>上级组织</span>
        <select
          :disabled="disabled" :value="modelValue.parentId ?? ''"
          @change="update('parentId', eventValue($event) || null)"
        >
          <option value="">顶级组织</option>
          <option v-for="node in nodes.filter((item) => item.id !== editingId)" :key="node.id" :value="node.id">
            {{ node.orgName }}（{{ node.orgCode }}）
          </option>
        </select>
      </label>
      <label>
        <span>状态</span>
        <select :disabled="disabled" :value="modelValue.status" @change="update('status', eventValue($event))">
          <option value="ACTIVE">启用</option>
          <option value="INACTIVE">停用</option>
        </select>
      </label>
      <label>
        <span>编制人数</span>
        <input
          min="0" type="number" :disabled="disabled" :value="modelValue.headcountPlan"
          @input="update('headcountPlan', Math.max(0, integer($event)))"
        >
      </label>
      <label>
        <span>排序号</span>
        <input type="number" :disabled="disabled" :value="modelValue.sortNo" @input="update('sortNo', integer($event))">
      </label>
      <label>
        <span>负责人员工 ID（可空）</span>
        <input
          :disabled="disabled" :value="modelValue.managerEmployeeId ?? ''"
          placeholder="由人员档案选择器回填"
          @input="update('managerEmployeeId', eventValue($event) || null)"
        >
      </label>
      <label class="org-editor__wide">
        <span>职责说明</span>
        <textarea
          maxlength="2000" rows="5" :disabled="disabled" :value="modelValue.description ?? ''"
          @input="update('description', eventValue($event) || null)"
        />
      </label>
    </div>

    <footer>
      <span>版本 {{ modelValue.expectedVersion }}</span>
      <button class="org-editor__primary" type="submit" :disabled="disabled">{{ submitLabel }}</button>
    </footer>
  </form>
</template>

<style scoped>
.org-editor { display: grid; gap: 1.2rem; }
.org-editor header, .org-editor footer { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.org-editor h2 { margin: .2rem 0 0; font-size: 1.25rem; }
.org-editor__eyebrow { margin: 0; color: var(--color-primary, #d76b2f); font-size: .75rem; font-weight: 800; letter-spacing: .12em; }
.org-editor__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .9rem; }
.org-editor label { display: grid; gap: .35rem; font-size: .82rem; font-weight: 650; }
.org-editor input, .org-editor select, .org-editor textarea { width: 100%; border: 1px solid var(--color-border, #e8ddd3); border-radius: .7rem; padding: .68rem .75rem; background: #fff; color: inherit; font: inherit; }
.org-editor textarea { resize: vertical; }
.org-editor__wide { grid-column: 1 / -1; }
.org-editor footer span { color: var(--color-text-muted, #776b62); font-size: .82rem; }
.org-editor__primary, .org-editor__ghost { border: 0; border-radius: .7rem; padding: .68rem 1rem; font: inherit; font-weight: 750; cursor: pointer; }
.org-editor__primary { background: var(--color-primary, #d76b2f); color: #fff; }
.org-editor__ghost { background: #f5efe8; color: inherit; }
.org-editor button:disabled, .org-editor input:disabled, .org-editor select:disabled, .org-editor textarea:disabled { cursor: not-allowed; opacity: .65; }
@media (max-width: 760px) { .org-editor__grid { grid-template-columns: 1fr; } .org-editor__wide { grid-column: auto; } }
</style>
