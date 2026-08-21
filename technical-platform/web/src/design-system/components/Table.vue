<script setup lang="ts">
withDefaults(defineProps<{
  caption?: string
  ariaLabel?: string
  empty?: boolean
  emptyText?: string
  columnCount?: number
}>(), {
  caption: undefined,
  ariaLabel: undefined,
  empty: false,
  emptyText: '暂无数据',
  columnCount: 1,
})
</script>

<template>
  <div class="sgj-table-wrap">
    <table class="sgj-table" :aria-label="caption ? undefined : ariaLabel">
      <caption v-if="caption">{{ caption }}</caption>
      <thead><slot name="head" /></thead>
      <tbody>
        <tr v-if="empty">
          <td class="sgj-table__empty" :colspan="columnCount"><slot name="empty">{{ emptyText }}</slot></td>
        </tr>
        <slot v-else name="body" />
      </tbody>
    </table>
  </div>
</template>
