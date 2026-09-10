<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { LateShipment } from '@/api/types'
import ConfidencePill from '@/components/ConfidencePill.vue'
import StagePill from '@/components/StagePill.vue'

const rows = ref<LateShipment[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await api.exceptions()
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <h1>Exceptions</h1>
  <p class="lede">Containers now predicted to land after the carrier's plan, worst first, with the commitments that fall due before the new arrival.</p>
  <div class="controls">
    <button @click="load" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span class="muted">{{ rows.length }} late containers</span>
    <span v-if="error" class="error">{{ error }}</span>
  </div>
  <section class="panel table-wrap">
    <table>
      <thead><tr><th>PO</th><th>Lane</th><th>Stage</th><th>Planned</th><th>Predicted</th><th class="num">Days late</th><th>Conf.</th><th class="num">Commitments at risk</th><th>Basis</th></tr></thead>
      <tbody>
        <tr v-for="r in rows" :key="r.shipmentId">
          <td><b>{{ r.poNumber }}</b></td>
          <td>{{ r.originPort }} → {{ r.destFc }}</td>
          <td><StagePill :value="r.currentStage" /></td>
          <td>{{ r.plannedArrival }}</td>
          <td>{{ r.predictedArrival }}</td>
          <td class="num error">+{{ r.daysLate }}</td>
          <td><ConfidencePill :value="r.confidence" /></td>
          <td class="num" :class="{ error: r.commitmentsDueBeforeArrival > 0 }">{{ r.commitmentsDueBeforeArrival }}</td>
          <td class="muted" style="white-space: normal; min-width: 260px; font-size: 12px">{{ r.basis }}</td>
        </tr>
        <tr v-if="!rows.length && !loading"><td colspan="9" class="muted">Nothing late. Post a milestone with an old date on the Inbound page, or refresh lane statistics, to see one.</td></tr>
      </tbody>
    </table>
  </section>
</template>
