<script setup lang="ts">
import type { Phase11Portal } from '../types'
import Phase11CaseBoard from '../shared/Phase11CaseBoard.vue'
import { P013_REWARD_CONFIG } from './p013-config'

defineProps<{ portal: Phase11Portal }>()
</script>

<template>
  <Phase11CaseBoard :portal="portal" :config="P013_REWARD_CONFIG" />
</template>
