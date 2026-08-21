<script setup lang="ts">
import { toRef } from 'vue'
import {
  SgjButton,
  SgjDashboardPageTemplate,
  SgjStatusChip,
  SgjTable,
} from '../../design-system'
import Phase10Feedback from '../phase10/components/Phase10Feedback.vue'
import Phase10Kpis from '../phase10/components/Phase10Kpis.vue'
import {
  usePhase10TechMonitor,
  type Phase10Process,
} from '../phase10/useTechMonitor'
import { statusTone } from '../phase10/shared'
import type { PortalDefinition } from '../portal-config'

const props = defineProps<{
  portal: PortalDefinition
  processes: readonly Phase10Process[]
}>()
const {
  rows, busy, feedback, failed, load, total, open, closed,
} = usePhase10TechMonitor(toRef(props, 'processes'))
</script>

<template>
  <SgjDashboardPageTemplate
    title="公共能力运行监控"
    description="只呈现服务端元数据、工作流节点和集成事实，不提供任何业务审批或专业认证动作。"
    data-testid="phase10-tech-monitor"
  >
    <template #actions>
      <SgjButton variant="secondary" size="sm" :loading="busy" @click="load">
        刷新监控
      </SgjButton>
    </template>
    <template #kpis><Phase10Kpis :total="total" :open="open" :closed="closed" /></template>
    <Phase10Feedback :message="feedback" :failed="failed" />
    <SgjTable
      caption="PHASE-10 公共能力运行投影"
      :empty="rows.length === 0"
      empty-text="当前数据范围内没有运行记录"
      :column-count="9"
    >
      <template #head>
        <tr>
          <th>流程</th><th>业务编号</th><th>节点</th><th>状态</th><th>中心</th>
          <th>员工</th><th>事实时间</th><th>集成事实</th><th>更新时间</th>
        </tr>
      </template>
      <template #body>
        <tr v-for="row in rows" :key="`${row.process}-${row.businessNo}`">
          <td>{{ row.process }}</td>
          <td>{{ row.businessNo }}</td>
          <td>{{ row.node }}</td>
          <td><SgjStatusChip :tone="statusTone(row.status)">{{ row.status }}</SgjStatusChip></td>
          <td>{{ row.ownerCenterId }}</td>
          <td>{{ row.ownerEmployeeId }}</td>
          <td>{{ row.period }}</td>
          <td>{{ row.integrationFact }}</td>
          <td>{{ row.updatedAt }}</td>
        </tr>
      </template>
    </SgjTable>
  </SgjDashboardPageTemplate>
</template>
