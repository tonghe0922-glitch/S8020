<script setup lang="ts">
import { SgjButton, SgjStatusChip, SgjTable } from '../../../../design-system'
import { displayNumber, displayTime, statusTone } from '../../shared'
import type { LeaveLedgerEntry } from '../useLeaveOperations'

defineProps<{
  entries: readonly LeaveLedgerEntry[]
  busy: boolean
}>()

defineEmits<{ refresh: [] }>()
</script>

<template>
  <div class="phase10-stack">
    <div class="phase10-toolbar">
      <p>额度账本只读展示；数据库触发器保证 append-only。</p>
      <SgjButton variant="secondary" size="sm" :loading="busy" @click="$emit('refresh')">刷新</SgjButton>
    </div>
    <SgjTable caption="请假额度账本" :empty="entries.length === 0" empty-text="暂无额度账本记录" :column-count="6">
      <template #head>
        <tr><th>业务编号</th><th>序号</th><th>类型</th><th>额度</th><th>说明</th><th>写入时间</th></tr>
      </template>
      <template #body>
        <tr v-for="entry in entries" :key="entry.id">
          <td>{{ entry.businessNo }}</td>
          <td class="sgj-cell--numeric">{{ entry.sequence }}</td>
          <td><SgjStatusChip :tone="statusTone(entry.entryType)">{{ entry.entryType }}</SgjStatusChip></td>
          <td class="sgj-cell--numeric">{{ displayNumber(entry.amount, ' 天') }}</td>
          <td>{{ entry.note || '-' }}</td>
          <td>{{ displayTime(entry.createdAt) }}</td>
        </tr>
      </template>
    </SgjTable>
  </div>
</template>
