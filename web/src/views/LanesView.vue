<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { LaneStats, RefreshSummary } from '@/api/types'
import StagePill from '@/components/StagePill.vue'
import { ptTime } from '@/lib/format'

const rows = ref<LaneStats[]>([])
const last = ref<RefreshSummary | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    ;[rows.value, last.value] = await Promise.all([api.laneStats(), api.lastRefresh()])
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <h1>Lane lead times</h1>
  <p class="lede">
    Transit-time distributions per lane and stage over the last 365 days of received containers, computed nightly
    by the dbt project (<code>analytics.lane_lead_time_stats</code>). Predictions use the P80; confidence grows
    with the sample size. As containers arrive - including the slow ones from a congested port - the numbers move.
  </p>
  <div class="controls">
    <button @click="load" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span class="muted" v-if="last?.statsComputedAt">statistics rebuilt {{ ptTime(last.statsComputedAt) }} ·</span>
    <span class="muted" v-if="last?.finishedAt">
      open containers last re-scored {{ ptTime(last.finishedAt) }}<template v-if="last.rescored">
      ({{ last.rescored.shipments }} scored, {{ last.rescored.changed }} predictions changed)</template>
    </span>
    <span class="muted" v-else>re-scored daily at 00:05 Vancouver time and after each nightly dbt build</span>
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
        <tr v-if="!rows.length && !loading"><td colspan="7" class="muted">Empty until the first dbt build. Without statistics, predictions fall back to the carrier plan with LOW confidence.</td></tr>
      </tbody>
    </table>
  </section>
</template>
