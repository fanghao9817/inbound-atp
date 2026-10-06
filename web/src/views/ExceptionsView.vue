<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { LateShipment, Shortage } from '@/api/types'
import ConfidencePill from '@/components/ConfidencePill.vue'
import StagePill from '@/components/StagePill.vue'
import { usePolling } from '@/composables/usePolling'

const rows = ref<LateShipment[]>([])
const shortages = ref<Shortage[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    ;[rows.value, shortages.value] = await Promise.all([api.exceptions(), api.shortages()])
    error.value = null
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
const { refresh } = usePolling(load, 60_000)
const overdue = computed(() => rows.value.filter((r) => r.reason === 'OVERDUE').length)
</script>

<template>
  <h1>Exceptions</h1>
  <p class="lede">What a planner would look at first: containers that will land after the carrier's plan or are already overdue, and the positions where committed demand will outrun supply.</p>
  <div class="controls">
    <button @click="refresh" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span class="muted">{{ rows.length }} late containers ({{ overdue }} overdue) · {{ shortages.length }} positions short</span>
    <span v-if="error" class="error">{{ error }}</span>
  </div>

  <section class="panel table-wrap">
    <h2>Late containers</h2>
    <table>
      <thead><tr><th>PO</th><th>Why</th><th>Lane</th><th>Stage</th><th>Planned</th><th>Predicted</th><th class="num">Days late</th><th>Conf.</th><th class="num">Orders due first</th><th>Basis</th></tr></thead>
      <tbody>
        <tr v-for="r in rows" :key="r.shipmentId">
          <td><b>{{ r.poNumber }}</b></td>
          <td><span class="pill" :class="r.reason === 'OVERDUE' ? 'bad' : 'warn'" :title="r.reason === 'OVERDUE' ? 'past its predicted date and still not at the FC' : 'predicted to arrive after the carrier plan'">{{ r.reason === 'OVERDUE' ? 'overdue' : 'late vs plan' }}</span></td>
          <td>{{ r.originPort }} → {{ r.destFc }}</td>
          <td><StagePill :value="r.currentStage" /></td>
          <td>{{ r.plannedArrival }}</td>
          <td>{{ r.predictedArrival }}</td>
          <td class="num error">+{{ r.daysLate }}</td>
          <td><ConfidencePill :value="r.confidence" /></td>
          <td class="num" :class="{ error: r.commitmentsDueBeforeArrival > 0 }">{{ r.commitmentsDueBeforeArrival }}</td>
          <td class="muted" style="white-space: normal; min-width: 260px; font-size: 12px">{{ r.basis }}</td>
        </tr>
        <tr v-if="!rows.length && !loading"><td colspan="10" class="muted">Nothing late right now.</td></tr>
      </tbody>
    </table>
  </section>

  <section class="panel table-wrap" style="margin-top: 16px">
    <h2>Shortages <span class="muted" style="font-weight: normal">(projected stock goes below zero within the horizon)</span></h2>
    <table>
      <thead><tr><th>SKU</th><th>FC</th><th>First short</th><th class="num">Units short</th><th class="num">Available now</th><th class="num">Inbound</th><th class="num">Committed</th></tr></thead>
      <tbody>
        <tr v-for="s in shortages" :key="s.sku + s.fc">
          <td><b>{{ s.sku }}</b></td><td>{{ s.fc }}</td><td>{{ s.firstShortDate }}</td>
          <td class="num error">{{ s.unitsShort }}</td><td class="num">{{ s.availableNow }}</td>
          <td class="num">{{ s.inboundUnits }}</td><td class="num">{{ s.committedUnits }}</td>
        </tr>
        <tr v-if="!shortages.length && !loading"><td colspan="7" class="muted">Every commitment is covered.</td></tr>
      </tbody>
    </table>
  </section>
</template>
