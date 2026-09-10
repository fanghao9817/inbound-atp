<script setup lang="ts">
import type { Confidence } from '@/api/types'
defineProps<{ value?: Confidence | null }>()
</script>

<template>
  <span v-if="value" class="pill" :class="value">{{ value }}</span>
  <span v-else class="muted">—</span>
</template>
