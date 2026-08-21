<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { PortalDefinition } from '../portal-config'
import { SgjButton, SgjEmpty, SgjPortalShell } from '../../design-system'

defineProps<{ portal: PortalDefinition }>()
const router = useRouter()
</script>

<template>
  <SgjPortalShell :portal-label="portal.title" page-title="页面不存在">
    <SgjEmpty title="未找到页面" description="该地址不存在或尚未在当前阶段实现。">
      <template #actions>
        <SgjButton variant="secondary" @click="router.replace('/')">返回首页</SgjButton>
      </template>
    </SgjEmpty>
  </SgjPortalShell>
</template>
