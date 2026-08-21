<script setup lang="ts">
import Avatar from './Avatar.vue'

withDefaults(defineProps<{
  name: string
  avatarSrc?: string
  subtitle?: string
  meta?: string
}>(), {
  avatarSrc: undefined,
  subtitle: undefined,
  meta: undefined,
})
</script>

<template>
  <div class="sgj-person-row">
    <Avatar :name="name" :src="avatarSrc" />
    <div class="sgj-person-row__content">
      <strong class="sgj-person-row__name">{{ name }}</strong>
      <span v-if="subtitle" class="sgj-person-row__subtitle">{{ subtitle }}</span>
      <span v-if="meta" class="sgj-person-row__meta">{{ meta }}</span>
    </div>
    <div v-if="$slots.actions" class="sgj-person-row__actions"><slot name="actions" /></div>
  </div>
</template>
