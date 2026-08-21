<script setup lang="ts">
import { computed } from 'vue'
import {
  SgjButton,
  SgjCard,
  SgjCheckbox,
  SgjDashboardPageTemplate,
  SgjInput,
  SgjKpiCard,
  SgjSelect,
  SgjStatusChip,
  SgjTable,
  SgjTextarea,
} from '../../../design-system'
import { statusTone } from '../../phase10/shared'
import type { Phase11Portal, Phase11Record } from '../types'
import { usePerformanceOperations } from './usePerformanceOperations'

const props = defineProps<{ portal: Phase11Portal }>()
const {
  rows, createForm, actionForm, scoreForm, total, open, closed, busy, feedback, failed,
  load, create, submitScore, act, canAct, actionsFor, canCreate,
} = usePerformanceOperations(props.portal)
const title = computed(() => ({
  employee: '我的绩效周期', center: '绩效管理工作台', tech: '绩效流程运行监控',
}[props.portal]))
const description = computed(() => ({
  employee: '确认目标、提交独立自评分数、查看反馈并按流程申诉。',
  center: '建立绩效周期，归集权威事实，完成评价、校准、影响执行与归档。',
  tech: '只读查看流程节点、版本与运行状态，不修改业务结论。',
}[props.portal]))
const scoreTypeOptions = computed(() => (props.portal === 'employee'
  ? [{ value: 'EMPLOYEE', label: '员工自评' }]
  : [
      { value: 'SUPERVISOR', label: '主管评价' },
      { value: 'AUTHORITATIVE', label: '权威数据评分' },
      { value: 'CALIBRATED', label: '校准分' },
    ]))

function scoreEligible(record: Phase11Record): boolean {
  return scoreForm.scoreType === 'CALIBRATED'
    ? record.currentNodeCode === 'S07'
    : record.currentNodeCode === 'S05'
}

function details(record: Phase11Record, key: string): string {
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
      <SgjKpiCard label="绩效周期" :value="total" unit="个" definition="当前权限范围内的真实周期" />
      <SgjKpiCard label="进行中" :value="open" unit="个" definition="当前节点未归档" tone="danger" />
      <SgjKpiCard label="已关闭" :value="closed" unit="个" definition="服务端节点为 END" tone="neutral" />
    </template>

    <p v-if="feedback" class="phase11-feedback" :class="{ 'phase11-feedback--error': failed }">
      {{ feedback }}
    </p>

    <SgjCard v-if="portal === 'center' && canCreate" class="phase11-panel">
      <template #header><h2>建立绩效周期</h2></template>
      <div class="phase11-form-grid">
        <SgjInput v-model="createForm.subject" label="周期主题" required />
        <SgjInput v-model="createForm.ownerEmployeeId" label="员工 ID" required />
        <SgjInput v-model="createForm.periodNo" label="周期编号" placeholder="2026-Q3" required />
        <SgjInput v-model="createForm.contentVersion" label="目标版本" required />
        <SgjInput v-model="createForm.factOccurredAt" label="事实起点" type="datetime-local" required />
      </div>
      <SgjTextarea v-model="createForm.reason" label="建立原因" required />
      <SgjTextarea v-model="createForm.goalSummary" label="绩效目标与衡量事实" required />
      <template #footer>
        <SgjButton :loading="busy" @click="create">创建并进入员工确认</SgjButton>
      </template>
    </SgjCard>

    <SgjCard class="phase11-panel">
      <template #header><h2>服务端绩效事实</h2></template>
      <SgjTable :empty="rows.length === 0" :column-count="8" empty-text="暂无绩效周期">
        <template #head>
          <tr><th>业务单号</th><th>主题</th><th>节点</th><th>版本</th><th>员工分</th><th>主管分</th><th>权威分</th><th>校准分</th></tr>
        </template>
        <template #body>
          <tr v-for="record in rows" :key="record.id">
            <td>{{ record.businessNo }}</td>
            <td>{{ record.subject }}</td>
            <td><SgjStatusChip :tone="statusTone(record.status)">{{ record.status }}</SgjStatusChip><small>{{ record.currentNodeCode }}</small></td>
            <td>{{ record.versionNo }}</td>
            <td>{{ details(record, 'employeeScore1000') }}</td>
            <td>{{ details(record, 'supervisorScore1000') }}</td>
            <td>{{ details(record, 'authoritativeScore1000') }}</td>
            <td>{{ details(record, 'calibratedScore1000') }}</td>
          </tr>
        </template>
      </SgjTable>
    </SgjCard>

    <template v-if="portal !== 'tech'" #aside>
      <SgjCard class="phase11-panel">
        <template #header><h2>独立分数事实</h2></template>
        <SgjSelect v-model="scoreForm.scoreType" label="分数类型" :options="scoreTypeOptions" required />
        <SgjInput v-model="scoreForm.score1000" label="1000 分制分数" type="number" min="0" max="1000" required />
        <SgjTextarea v-model="scoreForm.evidenceSummary" label="证据摘要" required />
        <SgjButton
          v-for="record in rows.filter(scoreEligible)"
          :key="`score-${record.id}`"
          variant="secondary"
          :loading="busy"
          @click="submitScore(record)"
        >为 {{ record.businessNo }} 登记分数</SgjButton>
      </SgjCard>

      <SgjCard class="phase11-panel">
        <template #header><h2>当前节点动作</h2></template>
        <SgjTextarea v-model="actionForm.summary" label="处理摘要" />
        <SgjTextarea v-model="actionForm.reason" label="工作流意见/原因" />
        <SgjCheckbox v-model="actionForm.appealRequested" label="员工申请独立复核" />
        <SgjTextarea v-model="actionForm.appealReason" label="申诉原因" />
        <SgjTextarea v-model="actionForm.decision" label="复核决定" />
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
