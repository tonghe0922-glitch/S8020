<script setup lang="ts">
import { SgjKpiCard } from '../../../design-system'

defineProps<{
  total: number
  open: number
  closed: number
}>()
</script>

<template>
  <div class="phase10-kpis" aria-label="流程运行概览">
    <SgjKpiCard label="全部记录" :value="total" unit="条" definition="当前数据范围内可见记录" />
    <SgjKpiCard label="办理中" :value="open" unit="条" definition="尚未进入 END 节点" />
    <SgjKpiCard label="已关闭" :value="closed" unit="条" definition="已完成服务端闭环" />
  </div>
</template>
