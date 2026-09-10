<script setup lang="ts">
import type { Stage } from '@/api/types'
const labels: Record<Stage, string> = {
  BOOKED: 'Booked',
  DEPARTED_ORIGIN: 'At sea',
  ARRIVED_DEST_PORT: 'At port',
  CUSTOMS_CLEARED: 'Cleared',
  RECEIVED_FC: 'Received',
}
defineProps<{ value?: Stage | null }>()
</script>

<template>
  <span v-if="value" class="pill stage">{{ labels[value] }}</span>
  <span v-else class="muted">—</span>
</template>
