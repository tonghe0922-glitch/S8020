<script setup lang="ts">
import type { PortalDefinition } from '../portal-config'
import { SgjNoPermission, SgjPortalShell } from '../../design-system'

defineProps<{ portal: PortalDefinition }>()
</script>

<template>
  <SgjPortalShell :portal-label="portal.title" page-title="无权限">
    <SgjNoPermission description="当前身份没有访问此页面的权限。权限结果以服务端裁决为准。" />
  </SgjPortalShell>
</template>
