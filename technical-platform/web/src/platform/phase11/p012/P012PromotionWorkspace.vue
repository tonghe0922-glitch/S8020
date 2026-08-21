<script setup lang="ts">
import { computed } from 'vue'
import {
  SgjButton,
  SgjCard,
  SgjCheckbox,
  SgjDashboardPageTemplate,
  SgjInput,
  SgjKpiCard,
  SgjStatusChip,
  SgjTable,
  SgjTextarea,
} from '../../../design-system'
import { statusTone } from '../../phase10/shared'
import type { Phase11Portal, Phase11Record } from '../types'
import { usePromotionOperations } from './usePromotionOperations'

const props = defineProps<{ portal: Phase11Portal }>()
const {
  rows, createForm, actionForm, total, open, closed, busy, feedback, failed,
  load, create, act, canAct, actionsFor, canCreate,
} = usePromotionOperations(props.portal)

const title = computed(() => ({
  employee: '我的晋升与任职发展',
  center: '晋升与任职发展工作台',
  tech: '晋升流程运行监控',
}[props.portal]))
const description = computed(() => ({
  employee: '查看本人提名、资格校验、评审和任职进度，并在规定节点确认任职安排。',
  center: '使用已关闭绩效事实完成资格校验、评审、任命、公示、任前校验与正式生效。',
  tech: '仅查看流程节点、版本与运行元数据，不参与晋升结论或任职生效。',
}[props.portal]))

function detail(record: Phase11Record, key: string): string {
  const value = record.details?.[key]
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}
</script>

<template>
  <SgjDashboardPageTemplate :title="title" :description="description">
    <template #actions>
      <SgjButton variant="secondary" :loading="busy" @click="load">刷新服务端事实</SgjButton>
    </template>

    <template #kpis>
      <SgjKpiCard label="晋升事项" :value="total" unit="个" definition="当前权限范围内的真实晋升事项" />
      <SgjKpiCard label="进行中" :value="open" unit="个" definition="尚未形成任职生效事实" tone="danger" />
      <SgjKpiCard label="已生效" :value="closed" unit="个" definition="服务端节点为 END 且已有任职事实" tone="neutral" />
    </template>

    <p v-if="feedback" class="phase11-feedback" :class="{ 'phase11-feedback--error': failed }">
      {{ feedback }}
    </p>

    <SgjCard v-if="portal !== 'tech' && canCreate" class="phase11-panel">
      <template #header><h2>建立晋升提名</h2></template>
      <div class="phase11-form-grid">
        <SgjInput v-model="createForm.subject" label="提名主题" required />
        <SgjInput
          v-if="portal === 'center'"
          v-model="createForm.ownerEmployeeId"
          label="候选员工 ID"
          required
        />
        <SgjInput v-model="createForm.sourcePerformanceCycleId" label="已关闭绩效周期 ID" required />
        <SgjInput v-model="createForm.currentPositionId" label="当前岗位 ID" />
        <SgjInput v-model="createForm.targetPositionId" label="目标岗位 ID" required />
        <SgjInput v-model="createForm.periodNo" label="晋升周期编号" placeholder="2026-Q3" required />
        <SgjInput v-model="createForm.contentVersion" label="规则/内容版本" required />
        <SgjInput
          v-model="createForm.promotionThresholdScore"
          label="晋升阈值（1000 分制）"
          type="number"
          min="0"
          max="1000"
          required
        />
        <SgjInput v-model="createForm.factOccurredAt" label="提名事实时间" type="datetime-local" required />
        <SgjInput v-model="createForm.appointmentEffectiveDate" label="计划任职日期" type="date" required />
      </div>
      <SgjTextarea v-model="createForm.reason" label="提名原因" required />
      <SgjTextarea v-model="createForm.nominationSummary" label="提名事实与依据" required />
      <SgjCheckbox
        v-if="portal === 'center'"
        v-model="createForm.ceoMode"
        label="总经理特殊任命模式（低于阈值时原因必须包含 [ceo_mode]）"
      />
      <template #footer>
        <SgjButton :loading="busy" @click="create">创建并进入资格校验</SgjButton>
      </template>
    </SgjCard>

    <SgjCard class="phase11-panel">
      <template #header><h2>服务端晋升与任职事实</h2></template>
      <SgjTable :empty="rows.length === 0" :column-count="9" empty-text="暂无晋升事项">
        <template #head>
          <tr>
            <th>业务单号</th><th>主题</th><th>节点</th><th>版本</th><th>绩效分</th>
            <th>阈值</th><th>目标岗位</th><th>计划生效</th><th>任职事实</th>
          </tr>
        </template>
        <template #body>
          <tr v-for="record in rows" :key="record.id">
            <td>{{ record.businessNo }}</td>
            <td>{{ record.subject }}</td>
            <td>
              <SgjStatusChip :tone="statusTone(record.status)">{{ record.status }}</SgjStatusChip>
              <small>{{ record.currentNodeCode }}</small>
            </td>
            <td>{{ record.versionNo }}</td>
            <td>{{ detail(record, 'weightedReviewScore') }}</td>
            <td>{{ detail(record, 'promotionThresholdScore') }}</td>
            <td>{{ detail(record, 'targetPositionId') }}</td>
            <td>{{ detail(record, 'appointmentEffectiveDate') }}</td>
            <td>{{ detail(record, 'appointmentEffectId') }}</td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal !== 'tech'" #aside>
      <SgjCard class="phase11-panel">
        <template #header><h2>当前节点处理</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要/事实依据" />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />
        <SgjTextarea v-model="actionForm.decision" label="任命审批决定" />
        <SgjInput v-model="actionForm.appointmentEffectiveDate" label="最终任职日期" type="date" />
        <div v-for="record in rows" :key="`actions-${record.id}`" class="phase11-action-group">
          <strong>{{ record.businessNo }} · {{ record.status }}</strong>
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
.phase11-panel { margin-bottom: 16px; }
.phase11-form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.phase11-feedback { padding: 12px 14px; border: 1px solid var(--sgj-border); border-radius: 10px; }
.phase11-feedback--error { font-weight: 700; }
.phase11-action-group { display: grid; gap: 8px; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--sgj-border); }
small { display: block; margin-top: 4px; }
</style>
