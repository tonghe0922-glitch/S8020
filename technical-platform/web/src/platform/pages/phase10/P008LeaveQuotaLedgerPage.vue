<script setup lang="ts">
import { SgjListPageTemplate } from '../../../design-system'
import Phase10Feedback from '../../phase10/components/Phase10Feedback.vue'
import LeaveLedgerTable from '../../phase10/p008/components/LeaveLedgerTable.vue'
import { useLeaveOperations } from '../../phase10/p008/useLeaveOperations'
import type { PortalDefinition } from '../../portal-config'

defineProps<{ portal: PortalDefinition }>()
const { ledger, busy, feedback, failed, load } = useLeaveOperations()
</script>

<template>
  <SgjListPageTemplate
    title="假期额度账本"
    description="查看额度预占、扣减、释放与差额调整的不可变流水。"
    data-testid="p008-leave-quota-ledger-page"
  >
    <Phase10Feedback :message="feedback" :failed="failed" />
    <LeaveLedgerTable :entries="ledger" :busy="busy" @refresh="load" />
  </SgjListPageTemplate>
</template>
