<script setup lang="ts">
import { computed } from 'vue'
import {
  SgjButton,
  SgjCard,
  SgjDashboardPageTemplate,
  SgjInput,
  SgjKpiCard,
  SgjStatusChip,
  SgjTable,
  SgjTextarea,
} from '../../../design-system'
import { statusTone } from '../../phase10/shared'
import type { Phase11Portal, Phase11Record } from '../types'
import {
  usePhase11CaseBoard,
  type Phase11CaseBoardConfig,
  type Phase11CaseColumn,
  type Phase11CaseField,
} from './usePhase11CaseBoard'

const props = defineProps<{
  portal: Phase11Portal
  config: Phase11CaseBoardConfig
}>()

const {
  rows, createForm, actionForm, total, open, closed, busy, feedback, failed,
  load, create, act, canAct, actionsFor, canCreate,
} = usePhase11CaseBoard(props.portal, props.config)

const title = computed(() => props.config.titles[props.portal])
const description = computed(() => props.config.descriptions[props.portal])
const visibleFields = computed(() => props.config.fields.filter(
  (field) => !field.centerOnly || props.portal === 'center',
))

function detail(record: Phase11Record, key: string): string {
  const value = record.details?.[key]
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return JSON.stringify(value)
}

function cell(record: Phase11Record, column: Phase11CaseColumn): string | number {
  if (column.source === 'details') return detail(record, column.key)
  const value = record[column.key as keyof Phase11Record]
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string' || typeof value === 'number') return value
  if (typeof value === 'boolean') return String(value)
  return '-'
}

function inputType(field: Phase11CaseField) {
  return field.kind === 'textarea' ? 'text' : field.kind
}
</script>

<template>
  <SgjDashboardPageTemplate :title="title" :description="description">
    <template #actions>
      <SgjButton variant="secondary" :loading="busy" @click="load">刷新权威事实</SgjButton>
    </template>

    <template #kpis>
      <SgjKpiCard label="事项总数" :value="total" unit="个" definition="当前数据权限范围内的服务端事项" />
      <SgjKpiCard label="进行中" :value="open" unit="个" definition="工作流节点尚未到 END" tone="danger" />
      <SgjKpiCard label="已关闭" :value="closed" unit="个" definition="服务端工作流已完成并归档" tone="neutral" />
    </template>

    <p v-if="feedback" class="case-board__feedback" :class="{ 'case-board__feedback--error': failed }">
      {{ feedback }}
    </p>

    <SgjCard v-if="portal !== 'tech' && canCreate" class="case-board__panel">
      <template #header><h2>登记服务端事项</h2></template>
      <div class="case-board__form">
        <template v-for="field in visibleFields" :key="field.key">
          <SgjTextarea
            v-if="field.kind === 'textarea'"
            v-model="createForm[field.key]"
            :label="field.label"
            :required="field.required"
            :placeholder="field.placeholder"
          />
          <SgjInput
            v-else
            v-model="createForm[field.key]"
            :label="field.label"
            :type="inputType(field)"
            :required="field.required"
            :placeholder="field.placeholder"
            :min="field.min"
            :max="field.max"
            :step="field.step"
          />
        </template>
      </div>
      <template #footer>
        <SgjButton :loading="busy" @click="create">创建并提交首节点</SgjButton>
      </template>
    </SgjCard>

    <SgjCard class="case-board__panel">
      <template #header><h2>权威流程事实</h2></template>
      <SgjTable
        :empty="rows.length === 0"
        :column-count="config.columns.length + 3"
        empty-text="暂无符合当前权限范围的事项"
      >
        <template #head>
          <tr>
            <th>业务单号</th><th>状态/节点</th><th>版本</th>
            <th v-for="column in config.columns" :key="column.key">{{ column.label }}</th>
          </tr>
        </template>
        <template #body>
          <tr v-for="record in rows" :key="record.id">
            <td>{{ record.businessNo }}</td>
            <td>
              <SgjStatusChip :tone="statusTone(record.status)">{{ record.status }}</SgjStatusChip>
              <small>{{ record.currentNodeCode }}</small>
            </td>
            <td>{{ record.versionNo }}</td>
            <td v-for="column in config.columns" :key="`${record.id}-${column.key}`">
              {{ cell(record, column) }}
            </td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal !== 'tech'" #aside>
      <SgjCard class="case-board__panel">
        <template #header><h2>当前节点处理</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要与事实依据" />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />
        <SgjTextarea v-model="actionForm.decision" label="审批或复核决定" />
        <SgjInput v-model="actionForm.financeReferenceId" label="权威财务事实 ID" />
        <SgjInput v-model="actionForm.receiptReference" label="回执编号" />
        <div v-for="record in rows" :key="`action-${record.id}`" class="case-board__actions">
          <strong>{{ record.businessNo }} · {{ record.currentNodeCode }}</strong>
          <SgjButton
            v-for="action in actionsFor(record).filter(canAct)"
            :key="action.code"
            :variant="action.tone ?? 'primary'"
            :loading="busy"
            @click="act(record, action)"
          >{{ action.label }}</SgjButton>
        </div>
      </SgjCard>
    </template>
  </SgjDashboardPageTemplate>
</template>

<style scoped>
.case-board__panel { margin-bottom: 16px; }
.case-board__form { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 12px; }
.case-board__feedback { padding: 12px 14px; border: 1px solid var(--sgj-border); border-radius: 10px; }
.case-board__feedback--error { font-weight: 700; }
.case-board__actions { display: grid; gap: 8px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--sgj-border); }
small { display: block; margin-top: 4px; }
</style>
