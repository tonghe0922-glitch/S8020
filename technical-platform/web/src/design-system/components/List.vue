<script setup lang="ts">
withDefaults(defineProps<{
  ordered?: boolean
  ariaLabel?: string
  divided?: boolean
}>(), {
  ordered: false,
  ariaLabel: undefined,
  divided: false,
})
</script>

<template>
  <component :is="ordered ? 'ol' : 'ul'" class="sgj-list" :class="{ 'sgj-list--divided': divided }" :aria-label="ariaLabel">
    <slot />
  </component>
</template>
