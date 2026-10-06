<script setup lang="ts">
import { computed, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { EtaPreview, PurchaseOrderView, ShipmentView, Stage } from '@/api/types'
import { STAGES } from '@/api/types'
import ConfidencePill from '@/components/ConfidencePill.vue'
import StagePill from '@/components/StagePill.vue'
import { usePolling } from '@/composables/usePolling'
import { ptTime } from '@/lib/format'

const orders = ref<PurchaseOrderView[]>([])
const status = ref<'OPEN' | 'RECEIVED' | 'ALL'>('OPEN')
const loading = ref(false)
const error = ref<string | null>(null)

const selected = ref<ShipmentView | null>(null)
const previewType = ref<Stage>('DEPARTED_ORIGIN')
const previewAt = ref(new Date().toISOString().slice(0, 10))
const preview = ref<EtaPreview | null>(null)
const previewError = ref<string | null>(null)

const today = new Date().toLocaleDateString('en-CA', { timeZone: 'America/Vancouver' })

async function load() {
  loading.value = true
  error.value = null
  try {
    orders.value = await api.purchaseOrders(status.value)
    if (selected.value) selected.value = await api.shipment(selected.value.shipment.id)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  } finally {
    loading.value = false
  }
}
const { refresh } = usePolling(load, 60_000)

async function open(po: PurchaseOrderView) {
  if (!po.shipmentId) return
  selected.value = await api.shipment(po.shipmentId)
  preview.value = null
  previewError.value = null
  const i = STAGES.indexOf(selected.value.shipment.currentStage)
  if (i >= 0 && i < STAGES.length - 1) previewType.value = STAGES[i + 1]!
}

/** Ask what the prediction would become if the next milestone happened on the chosen day. Nothing is saved. */
async function runPreview() {
  if (!selected.value) return
  preview.value = null
  previewError.value = null
  try {
    const occurredAt = new Date(`${previewAt.value}T12:00:00-07:00`).toISOString()
    preview.value = await api.etaPreview(selected.value.shipment.id, { type: previewType.value, occurredAt })
  } catch (e) {
    previewError.value = e instanceof ApiError ? e.detail : String(e)
  }
}

const isOverdue = (po: PurchaseOrderView) => po.status === 'OPEN' && !!po.predictedArrival && po.predictedArrival < today
const lateCount = computed(() => orders.value.filter((o) => (o.daysLate ?? 0) > 0).length)
const overdueCount = computed(() => orders.value.filter(isOverdue).length)
const lagHours = (m: { occurredAt: string; recordedAt: string }) =>
  Math.max(0, Math.round((new Date(m.recordedAt).getTime() - new Date(m.occurredAt).getTime()) / 3_600_000))
</script>

<template>
  <h1>Inbound purchase orders</h1>
  <p class="lede">
    Every container on the way, with the carrier's plan next to our prediction. Milestones arrive from the carrier,
    customs and warehouse feeds (simulated) through the internal API → Kafka → the ETA consumer re-predicts.
  </p>

  <div class="controls">
    <label class="field">Status
      <select v-model="status" @change="refresh"><option>OPEN</option><option>RECEIVED</option><option>ALL</option></select>
    </label>
    <button @click="refresh" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span class="muted">{{ orders.length }} orders · {{ lateCount }} predicted late · {{ overdueCount }} overdue</span>
    <span v-if="error" class="error">{{ error }}</span>
  </div>

  <div class="grid cols-2">
    <section class="panel table-wrap">
      <table>
        <thead>
          <tr><th>PO</th><th>Lane</th><th>Stage</th><th>Planned</th><th>Predicted</th><th class="num">Δ days</th><th>Conf.</th><th>Lines</th></tr>
        </thead>
        <tbody>
          <tr v-for="po in orders" :key="po.id" class="selectable" :class="{ selected: selected?.shipment.id === po.shipmentId }" @click="open(po)">
            <td><b>{{ po.poNumber }}</b><div class="muted" style="font-size: 12px">{{ po.supplier }}</div></td>
            <td>{{ po.originPort }} → {{ po.destFc }}</td>
            <td><StagePill :value="po.currentStage" /></td>
            <td>{{ po.plannedArrival }}</td>
            <td>{{ po.predictedArrival ?? '—' }} <span v-if="isOverdue(po)" class="pill bad" title="past its predicted date and still not at the FC">overdue</span></td>
            <td class="num" :class="{ error: (po.daysLate ?? 0) > 0, muted: !po.daysLate }">{{ po.daysLate == null ? '—' : (po.daysLate > 0 ? '+' : '') + po.daysLate }}</td>
            <td><ConfidencePill :value="po.predictedConfidence" /></td>
            <td class="muted" style="font-size: 12px">{{ po.lines.map((l) => `${l.skuCode} ×${po.status === 'OPEN' ? l.qtyOrdered - l.qtyReceived : l.qtyReceived}`).join(', ') }}</td>
          </tr>
          <tr v-if="!orders.length && !loading"><td colspan="8" class="muted">No purchase orders.</td></tr>
        </tbody>
      </table>
    </section>

    <section class="panel" v-if="selected">
      <h2>Container {{ selected.shipment.containerNo }} · {{ selected.shipment.carrier }}</h2>
      <div class="stats">
        <div class="stat"><span class="muted">Lane</span><b>{{ selected.lane.originPort }} → {{ selected.lane.destFcCode }}</b></div>
        <div class="stat"><span class="muted">Stage</span><b><StagePill :value="selected.shipment.currentStage" /></b></div>
        <div class="stat"><span class="muted">Planned</span><b>{{ selected.shipment.plannedArrival }}</b></div>
        <div class="stat"><span class="muted">Predicted</span><b>{{ selected.shipment.predictedArrival ?? '—' }} <ConfidencePill :value="selected.shipment.predictedConfidence" /></b></div>
      </div>
      <p class="note" v-if="selected.shipment.predictionBasis">Basis: {{ selected.shipment.predictionBasis }}</p>

      <h2 style="margin-top: 14px">Milestones</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Happened</th><th>Reported by</th><th class="num">Lag</th></tr></thead>
          <tbody>
            <tr v-for="m in selected.milestones" :key="m.id">
              <td><StagePill :value="m.type" /></td><td>{{ ptTime(m.occurredAt) }}</td><td class="muted">{{ m.source }}</td>
              <td class="num muted" :title="`received ${ptTime(m.recordedAt)}`">{{ lagHours(m) }} h</td>
            </tr>
            <tr v-if="!selected.milestones.length"><td colspan="4" class="muted">No milestones yet.</td></tr>
          </tbody>
        </table>
      </div>
      <p class="note">Lag = how long after the event the message reached us. EDI typically arrives hours late; the prediction uses when it happened.</p>

      <template v-if="selected.shipment.currentStage !== 'RECEIVED_FC'">
        <h2 style="margin-top: 14px">What if…</h2>
        <div class="controls" style="margin-bottom: 6px">
          <label class="field">the container reached
            <select v-model="previewType"><option v-for="s in STAGES.slice(1)" :key="s" :value="s">{{ s }}</option></select>
          </label>
          <label class="field">on
            <input v-model="previewAt" type="date" />
          </label>
          <button class="primary" @click="runPreview">Preview the ETA</button>
        </div>
        <p v-if="preview" class="note">
          Prediction would move from <b>{{ preview.currentArrival ?? '—' }}</b> <ConfidencePill :value="preview.currentConfidence" />
          to <b>{{ preview.previewArrival ?? '—' }}</b> <ConfidencePill :value="preview.previewConfidence" />. {{ preview.basis }}
        </p>
        <p v-else-if="previewError" class="error">{{ previewError }}</p>
        <p v-else class="note">Runs the same predictor without recording anything: milestones only come from the carrier and warehouse feeds.</p>
      </template>
    </section>
  </div>
</template>
