<script setup lang="ts">
import type { NavigationIconKey } from './navigation-catalog'

const props = defineProps<{ icon: NavigationIconKey }>()

const paths: Readonly<Record<NavigationIconKey, readonly string[]>> = {
  home: ['m3 10.5 9-7.5 9 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z', 'M9 21v-7h6v7'],
  contacts: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  tasks: ['M8 2v4M16 2v4M3 10h18', 'M5 4h14a2 2 0 0 1 2 2v14H3V6a2 2 0 0 1 2-2', 'm9 16 2 2 4-5'],
  approvals: ['M9 11l3 3L22 4', 'M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11'],
  notices: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M8 13h8M8 17h6'],
  meetings: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'm17 11 2 2 3-3'],
  learning: ['M4 19.5A2.5 2.5 0 0 1 6.5 17H20', 'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2'],
  shows: ['M9 18V5l12-2v13', 'M9 9l12-2', 'M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6', 'M18 19a3 3 0 1 0 0-6 3 3 0 0 0 0 6'],
  expense: ['M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7H14a3.5 3.5 0 0 1 0 7H6'],
  services: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18', 'M8 14s1.5 2 4 2 4-2 4-2', 'M9 9h.01M15 9h.01'],
  messages: ['M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z', 'M8 8h8M8 12h5'],
  center: ['M3 21h18M5 21V7l7-4 7 4v14', 'M9 10h.01M12 10h.01M15 10h.01M9 14h.01M12 14h.01M15 14h.01', 'M10 21v-3h4v3'],
  authz: ['M12 2 4 5v6c0 5 3.5 8.5 8 9 4.5-.5 8-4 8-9V5z', 'm9 12 2 2 4-4'],
  security: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10', 'M9 12h6M12 9v6'],
  operations: ['M4 6h16M4 12h16M4 18h16', 'M7 3v6M17 9v6M10 15v6'],
}
</script>

<template>
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path v-for="path in paths[props.icon]" :key="path" :d="path" />
  </svg>
</template>
