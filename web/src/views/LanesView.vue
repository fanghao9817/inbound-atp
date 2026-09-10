<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { LaneStats, RecalcSummary } from '@/api/types'
import StagePill from '@/components/StagePill.vue'

const rows = ref<LaneStats[]>([])
const loading = ref(false)
const recalc = ref<RecalcSummary | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await api.laneStats()
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function rescore() {
  busy.value = true
  try {
    recalc.value = await api.recalculateAll()
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>

<template>
  <h1>Lane lead times</h1>
  <p class="lede">
    Transit-time distributions per lane and stage, computed by the dbt project from a year of received containers
    (<code>analytics.lane_lead_time_stats</code>). Predictions use the P80; confidence grows with the sample size.
  </p>
  <div class="controls">
    <button @click="load" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <button class="primary" @click="rescore" :disabled="busy">{{ busy ? 'Re-scoring…' : 'Re-score open shipments' }}</button>
    <span v-if="recalc" class="muted">{{ recalc.shipments }} open shipments scored, {{ recalc.changed }} predictions changed</span>
    <span v-if="error" class="error">{{ error }}</span>
  </div>
  <section class="panel table-wrap">
    <table>
      <thead><tr><th>Origin</th><th>Destination FC</th><th>From</th><th>To</th><th class="num">P50 days</th><th class="num">P80 days</th><th class="num">Samples</th></tr></thead>
      <tbody>
        <tr v-for="r in rows" :key="r.originPort + r.destFcCode + r.fromStage">
          <td>{{ r.originPort }}</td><td>{{ r.destFcCode }}</td>
          <td><StagePill :value="r.fromStage" /></td><td><StagePill :value="r.toStage" /></td>
          <td class="num">{{ r.p50Days }}</td><td class="num"><b>{{ r.p80Days }}</b></td><td class="num">{{ r.sampleN }}</td>
        </tr>
        <tr v-if="!rows.length && !loading"><td colspan="7" class="muted">Empty until the first <code>dbt run</code>. Without statistics, predictions fall back to the carrier plan with LOW confidence.</td></tr>
      </tbody>
    </table>
  </section>
</template>
