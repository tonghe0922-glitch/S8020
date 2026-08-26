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
  embedded?: boolean
  showHeader?: boolean
  showFooter?: boolean
}>(), {
  editingId: '',
  disabled: false,
  embedded: false,
  showHeader: true,
  showFooter: true,
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
  <form class="org-editor" :class="{ 'is-embedded': embedded }" @submit.prevent="emit('submit')">
    <header v-if="showHeader" class="org-editor__header">
      <div>
        <p class="org-editor__eyebrow">ORGANIZATION PROFILE</p>
        <h2>{{ title }}</h2>
        <span>组织编码由系统统一生成，其余信息保存后立即进入正式组织数据。</span>
      </div>
      <button type="button" class="org-editor__ghost" @click="emit('cancel')">取消编辑</button>
    </header>

    <section class="org-editor__section">
      <div class="org-editor__section-title">
        <span class="org-editor__section-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4.5 20V6.8c0-.7.5-1.3 1.2-1.5l6-1.8c1-.3 2 .4 2 1.5v15M14 9.2l4.2 1.2c.8.2 1.3.9 1.3 1.7V20M2.8 20h18.4M8 8.5h2M8 12h2M8 15.5h2" /></svg>
        </span>
        <div><strong>基本信息</strong><small>维护组织名称、类型、归属和运行状态</small></div>
      </div>

      <div class="org-editor__grid">
        <label>
          <span>组织编码（系统自动生成）</span>
          <input
            class="is-readonly"
            disabled
            readonly
            :value="modelValue.orgCode || '保存后自动生成（如 S06-ORG-060）'"
            aria-describedby="org-code-help"
          >
          <small id="org-code-help">组织编码由服务端按租户序列统一分配，不能手工修改。</small>
        </label>
        <label>
          <span>组织名称 <b>*</b></span>
          <input
            required maxlength="128" :disabled="disabled" :value="modelValue.orgName"
            placeholder="请输入组织名称"
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
          <span>运行状态</span>
          <select :disabled="disabled" :value="modelValue.status" @change="update('status', eventValue($event))">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option>
          </select>
        </label>
        <label>
          <span>排序号</span>
          <input type="number" :disabled="disabled" :value="modelValue.sortNo" @input="update('sortNo', integer($event))">
        </label>
      </div>
    </section>

    <section class="org-editor__section">
      <div class="org-editor__section-title">
        <span class="org-editor__section-icon is-blue" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M16 20v-1.7a3.8 3.8 0 0 0-3.8-3.8H7.3a3.8 3.8 0 0 0-3.8 3.8V20M9.8 10.8a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8ZM16.5 4.2a3.4 3.4 0 0 1 0 6.4M20.5 20v-1.7a3.8 3.8 0 0 0-2.8-3.7" /></svg>
        </span>
        <div><strong>管理与编制</strong><small>设置负责人、规划编制和组织职责</small></div>
      </div>

      <div class="org-editor__grid">
        <label>
          <span>编制人数</span>
          <input
            min="0" type="number" :disabled="disabled" :value="modelValue.headcountPlan"
            @input="update('headcountPlan', Math.max(0, integer($event)))"
          >
        </label>
        <label>
          <span>负责人员工 ID</span>
          <input
            :disabled="disabled" :value="modelValue.managerEmployeeId ?? ''"
            placeholder="由人员档案选择器回填，可暂不设置"
            @input="update('managerEmployeeId', eventValue($event) || null)"
          >
        </label>
        <label class="org-editor__wide">
          <span>职责说明</span>
          <textarea
            maxlength="2000" rows="6" :disabled="disabled" :value="modelValue.description ?? ''"
            placeholder="请输入该组织的职责范围、协同边界和重点工作"
            @input="update('description', eventValue($event) || null)"
          />
          <small>{{ (modelValue.description ?? '').length }}/2000</small>
        </label>
      </div>
    </section>

    <footer v-if="showFooter" class="org-editor__footer">
      <span>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.7a6.3 6.3 0 1 0 0 12.6A6.3 6.3 0 0 0 8 1.7Zm0 3v3.7l2.4 1.4" /></svg>
        当前数据版本 {{ modelValue.expectedVersion }}，保存时将进行并发校验
      </span>
      <div>
        <button type="button" class="org-editor__ghost" @click="emit('cancel')">取消</button>
        <button class="org-editor__primary" type="submit" :disabled="disabled">
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.3 2.3h9.5l1.9 1.9v9.5H2.3zM4.5 2.3v4h6v-4M5 10.5h6" /></svg>
          {{ submitLabel }}
        </button>
      </div>
    </footer>
  </form>
</template>

<style scoped>
.org-editor { display: grid; gap: 1rem; color: #273248; }
.org-editor__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; padding-bottom: 1.05rem; border-bottom: 1px solid #edf0f4; }
.org-editor__header h2 { margin: .22rem 0 .28rem; font-size: 1.3rem; }
.org-editor__header > div > span { color: #8a94a5; font-size: .76rem; }
.org-editor__eyebrow { margin: 0; color: #db6832; font-size: .64rem; font-weight: 800; letter-spacing: .14em; }
.org-editor__section { display: grid; gap: 1rem; padding: 1rem; border: 1px solid #e7eaf0; border-radius: .9rem; background: #fff; }
.org-editor__section-title { display: flex; align-items: center; gap: .65rem; }
.org-editor__section-title > div { display: grid; gap: .08rem; }
.org-editor__section-title strong { font-size: .88rem; }
.org-editor__section-title small { color: #929baa; font-size: .68rem; }
.org-editor__section-icon { display: grid; place-items: center; width: 2rem; height: 2rem; border-radius: .58rem; background: #fff0e8; color: #d7632c; }
.org-editor__section-icon.is-blue { background: #eaf3ff; color: #4677b1; }
.org-editor__section-icon svg { width: 1.1rem; height: 1.1rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.55; }
.org-editor__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .9rem 1rem; }
.org-editor label { display: grid; align-content: start; gap: .38rem; color: #59657a; font-size: .75rem; font-weight: 650; }
.org-editor label > span b { color: #df563d; }
.org-editor input,
.org-editor select,
.org-editor textarea { width: 100%; border: 1px solid #dfe3ea; border-radius: .62rem; padding: .68rem .75rem; outline: none; background: #fff; color: #2b3548; font: inherit; font-size: .79rem; transition: border-color .16s ease, box-shadow .16s ease; }
.org-editor input:focus,
.org-editor select:focus,
.org-editor textarea:focus { border-color: #e57a45; box-shadow: 0 0 0 3px #fff0e8; }
.org-editor textarea { min-height: 7.8rem; line-height: 1.65; resize: vertical; }
.org-editor label > small { justify-self: end; color: #9aa3b1; font-size: .64rem; font-weight: 500; line-height: 1.45; }
.org-editor input.is-readonly { background: #f5f6f8; color: #7f8999; opacity: 1; }
.org-editor__wide { grid-column: 1 / -1; }
.org-editor__footer { position: sticky; z-index: 5; bottom: 0; display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin: .15rem -1.05rem -1.05rem; padding: .85rem 1.05rem; border-top: 1px solid #e8ebf0; border-radius: 0 0 .95rem .95rem; background: rgb(255 255 255 / 96%); box-shadow: 0 -10px 24px rgb(36 47 66 / 5%); backdrop-filter: blur(10px); }
.org-editor__footer > span { display: flex; align-items: center; gap: .35rem; color: #8b95a5; font-size: .67rem; }
.org-editor__footer > span svg { width: .85rem; height: .85rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.35; }
.org-editor__footer > div { display: flex; gap: .55rem; }
.org-editor__primary,
.org-editor__ghost { display: inline-flex; align-items: center; justify-content: center; gap: .35rem; border-radius: .62rem; padding: .64rem .9rem; font: inherit; font-size: .75rem; font-weight: 750; cursor: pointer; }
.org-editor__primary { border: 1px solid #d95e28; background: linear-gradient(135deg, #ed7a42, #d95e28); color: #fff; box-shadow: 0 8px 18px rgb(210 88 35 / 18%); }
.org-editor__primary svg { width: .9rem; height: .9rem; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.45; }
.org-editor__ghost { border: 1px solid #dfe3e9; background: #fff; color: #657085; }
.org-editor button:disabled,
.org-editor input:disabled,
.org-editor select:disabled,
.org-editor textarea:disabled { cursor: not-allowed; opacity: .62; }
.org-editor.is-embedded { gap: .85rem; }
.org-editor.is-embedded .org-editor__section { border-color: #edf0f4; box-shadow: none; }
@media (max-width: 760px) {
  .org-editor__grid { grid-template-columns: 1fr; }
  .org-editor__wide { grid-column: auto; }
  .org-editor__header,
  .org-editor__footer { align-items: flex-start; flex-direction: column; }
  .org-editor__footer > div { width: 100%; }
  .org-editor__footer button { flex: 1; }
}
</style>
