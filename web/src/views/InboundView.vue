<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { PurchaseOrderView, ShipmentView, Stage } from '@/api/types'
import { STAGES } from '@/api/types'
import ConfidencePill from '@/components/ConfidencePill.vue'
import StagePill from '@/components/StagePill.vue'

const orders = ref<PurchaseOrderView[]>([])
const status = ref<'OPEN' | 'RECEIVED' | 'ALL'>('OPEN')
const loading = ref(false)
const error = ref<string | null>(null)

const selected = ref<ShipmentView | null>(null)
const milestoneType = ref<Stage>('DEPARTED_ORIGIN')
const occurredAt = ref(new Date().toISOString().slice(0, 16))
const posting = ref(false)
const lastResult = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    orders.value = await api.purchaseOrders(status.value)
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  } finally {
    loading.value = false
  }
}

async function open(po: PurchaseOrderView) {
  if (!po.shipmentId) return
  selected.value = await api.shipment(po.shipmentId)
  lastResult.value = null
  const next = nextStage(selected.value.shipment.currentStage)
  if (next) milestoneType.value = next
}

function nextStage(stage: Stage): Stage | null {
  const i = STAGES.indexOf(stage)
  return i >= 0 && i < STAGES.length - 1 ? STAGES[i + 1]! : null
}

/** Post the milestone, then poll until the Kafka consumer has written a new prediction. */
async function postMilestone() {
  if (!selected.value) return
  posting.value = true
  lastResult.value = null
  const id = selected.value.shipment.id
  const before = selected.value.shipment.version
  try {
    const res = await api.postMilestone(id, { type: milestoneType.value, occurredAt: new Date(occurredAt.value).toISOString(), source: 'MANUAL' })
    lastResult.value = res.accepted ? `Accepted (event ${res.eventId.slice(0, 8)}…) — waiting for the ETA consumer` : res.note
    if (res.accepted) {
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 500))
        const view = await api.shipment(id)
        if (view.shipment.version > before + 1) {
          selected.value = view
          lastResult.value = `Prediction updated: ${view.shipment.predictedArrival} (${view.shipment.predictedConfidence})`
          break
        }
      }
      await load()
    }
  } catch (e) {
    lastResult.value = e instanceof ApiError ? e.detail : String(e)
  } finally {
    posting.value = false
  }
}

const lateCount = computed(() => orders.value.filter((o) => (o.daysLate ?? 0) > 0).length)

onMounted(load)
</script>

<template>
  <h1>Inbound purchase orders</h1>
  <p class="lede">Every container on the water, with the carrier's plan next to our prediction. Post a milestone to watch the prediction update through Kafka.</p>

  <div class="controls">
    <label class="field">Status
      <select v-model="status" @change="load"><option>OPEN</option><option>RECEIVED</option><option>ALL</option></select>
    </label>
    <button @click="load" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span class="muted">{{ orders.length }} orders · {{ lateCount }} predicted late</span>
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
            <td>{{ po.predictedArrival ?? '—' }}</td>
            <td class="num" :class="{ error: (po.daysLate ?? 0) > 0, muted: !po.daysLate }">{{ po.daysLate == null ? '—' : (po.daysLate > 0 ? '+' : '') + po.daysLate }}</td>
            <td><ConfidencePill :value="po.predictedConfidence" /></td>
            <td class="muted" style="font-size: 12px">{{ po.lines.map((l) => `${l.skuCode} ×${l.qtyOrdered - l.qtyReceived}`).join(', ') }}</td>
          </tr>
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
          <thead><tr><th>Type</th><th>Occurred</th><th>Source</th></tr></thead>
          <tbody>
            <tr v-for="m in selected.milestones" :key="m.id"><td><StagePill :value="m.type" /></td><td>{{ m.occurredAt.slice(0, 16).replace('T', ' ') }}</td><td class="muted">{{ m.source }}</td></tr>
            <tr v-if="!selected.milestones.length"><td colspan="3" class="muted">No milestones yet.</td></tr>
          </tbody>
        </table>
      </div>

      <h2 style="margin-top: 14px">Record a milestone</h2>
      <div class="controls" style="margin-bottom: 6px">
        <label class="field">Type
          <select v-model="milestoneType"><option v-for="s in STAGES" :key="s" :value="s">{{ s }}</option></select>
        </label>
        <label class="field">Occurred at
          <input v-model="occurredAt" type="datetime-local" />
        </label>
        <button class="primary" @click="postMilestone" :disabled="posting || selected.shipment.currentStage === 'RECEIVED_FC'">{{ posting ? 'Posting…' : 'Post milestone' }}</button>
      </div>
      <p class="note">{{ lastResult ?? 'POST /api/shipments/{id}/milestones → Kafka shipment.milestones → ETA consumer → shipment.eta-updated. Replaying the same eventId is a no-op.' }}</p>
    </section>
  </div>
</template>
