<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, ApiError } from '@/api/client'
import type { AppConfig, AtpQuote, FcSummary, FulfillmentResponse, StorefrontAvailability } from '@/api/types'
import { useCatalogStore } from '@/stores/catalog'
import ConfidencePill from '@/components/ConfidencePill.vue'
import StagePill from '@/components/StagePill.vue'

const catalog = useCatalogStore()
const sku = ref('SOFA-3S-OAT')
const qty = ref(5)
const selectedFc = ref<string | null>(null)

const summary = ref<FcSummary[]>([])
const quote = ref<AtpQuote | null>(null)
const fulfillment = ref<FulfillmentResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const config = ref<AppConfig | null>(null)
const storefront = ref<StorefrontAvailability | null>(null)
const storefrontError = ref<string | null>(null)
const projecting = ref(false)

async function loadStorefront() {
  if (!config.value?.availabilityUrl) return
  storefrontError.value = null
  try {
    storefront.value = await api.storefront(config.value.availabilityUrl, sku.value)
  } catch (e) {
    storefront.value = null
    storefrontError.value = e instanceof ApiError ? e.detail : String(e)
  }
}

async function projectAll() {
  projecting.value = true
  try {
    await api.projectAll()
    await new Promise((r) => setTimeout(r, 1500)) // the Lambda is invoked asynchronously
    await loadStorefront()
  } finally {
    projecting.value = false
  }
}

const skuName = computed(() => catalog.skus.find((s) => s.code === sku.value)?.name ?? '')

async function load() {
  loading.value = true
  error.value = null
  try {
    summary.value = await api.atpSummary(sku.value, qty.value)
    if (!selectedFc.value || !summary.value.some((s) => s.fc === selectedFc.value)) {
      selectedFc.value = summary.value.find((s) => s.promisable)?.fc ?? summary.value[0]?.fc ?? null
    }
    await loadDetail()
  } catch (e) {
    error.value = e instanceof ApiError ? e.detail : String(e)
  } finally {
    loading.value = false
  }
}

async function loadDetail() {
  if (!selectedFc.value) return
  ;[quote.value, fulfillment.value] = await Promise.all([
    api.atp(sku.value, selectedFc.value, qty.value),
    api.fulfillmentQuote({ sku: sku.value, fc: selectedFc.value, requestedQuantity: qty.value }),
  ])
}

function select(fc: string) {
  selectedFc.value = fc
  loadDetail().catch((e) => (error.value = String(e)))
}

onMounted(async () => {
  await catalog.load()
  config.value = await api.config().catch(() => null)
  await Promise.all([load(), loadStorefront()])
})
watch([sku, qty], () => load())
watch(sku, () => loadStorefront())
</script>

<template>
  <h1>Availability</h1>
  <p class="lede">
    When can we promise <b>{{ qty }}</b> × <b>{{ sku }}</b> <span class="muted">{{ skuName }}</span> at each fulfillment center?
    On-hand stock first, then inbound containers at their <i>predicted</i> arrival, minus demand that is already committed.
  </p>

  <div class="controls">
    <label class="field">SKU
      <select v-model="sku">
        <option v-for="s in catalog.skus" :key="s.code" :value="s.code">{{ s.code }} — {{ s.name }}</option>
      </select>
    </label>
    <label class="field">Quantity
      <input v-model.number="qty" type="number" min="1" max="10000" />
    </label>
    <button @click="load" :disabled="loading">{{ loading ? 'Loading…' : 'Refresh' }}</button>
    <span v-if="error" class="error">{{ error }}</span>
  </div>

  <div class="grid cols-2">
    <section class="panel">
      <h2>By fulfillment center</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>FC</th><th class="num">Available now</th><th>Promise date for {{ qty }}</th></tr></thead>
          <tbody>
            <tr v-for="s in summary" :key="s.fc" class="selectable" :class="{ selected: s.fc === selectedFc }" @click="select(s.fc)">
              <td><b>{{ s.fc }}</b> <span class="muted">{{ s.fcName }}</span></td>
              <td class="num">{{ s.availableNow }}</td>
              <td>
                <span v-if="s.promisable" class="pill" :class="s.promiseDate && s.promiseDate <= quote?.timeline[0]?.date! ? 'ok' : 'warn'">{{ s.promiseDate }}</span>
                <span v-else class="pill bad">not within horizon</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="note">Click a row to see how the date was derived.</p>
    </section>

    <section class="panel" v-if="quote">
      <h2>{{ quote.fc }} — time-phased ATP</h2>
      <div class="stats">
        <div class="stat"><span class="muted">Available now</span><b>{{ quote.availableNow }}</b></div>
        <div class="stat"><span class="muted">Promise date</span><b>{{ quote.promiseDate ?? '—' }}</b></div>
        <div class="stat"><span class="muted">Horizon</span><b>{{ quote.horizonDays }} d</b></div>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Date</th><th class="num">Projected stock</th><th class="num">ATP (look-ahead min)</th></tr></thead>
          <tbody>
            <tr v-for="p in quote.timeline" :key="p.date">
              <td>{{ p.date }}</td>
              <td class="num">{{ p.projected }}</td>
              <td class="num" :class="{ error: p.atp < qty }">{{ p.atp }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="note">
        ATP on a date is the minimum projected stock from that date onward, so stock that a later commitment needs is never promised twice.
      </p>

      <h2 style="margin-top: 16px">Inbound supply counted</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>PO</th><th class="num">Qty</th><th>Arrives (incl. dock-to-stock)</th><th>Stage</th><th>Confidence</th></tr></thead>
          <tbody>
            <tr v-for="s in quote.supplies" :key="s.poNumber">
              <td>{{ s.poNumber }}</td><td class="num">{{ s.qty }}</td><td>{{ s.arrives }}</td>
              <td><StagePill :value="s.stage" /></td><td><ConfidencePill :value="s.confidence" /></td>
            </tr>
            <tr v-if="!quote.supplies.length"><td colspan="5" class="muted">No open purchase orders for this SKU here.</td></tr>
          </tbody>
        </table>
      </div>

      <h2 style="margin-top: 16px">Committed demand subtracted</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Reference</th><th class="num">Qty</th><th>Need by</th></tr></thead>
          <tbody>
            <tr v-for="d in quote.demands" :key="d.reference"><td>{{ d.reference }}</td><td class="num">{{ d.qty }}</td><td>{{ d.needBy }}</td></tr>
            <tr v-if="!quote.demands.length"><td colspan="3" class="muted">No commitments within the horizon.</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>

  <section class="panel" v-if="fulfillment" style="margin-top: 16px">
    <h2>Fulfillment quote (plain-JDBC read path)</h2>
    <p class="note" style="margin: 0 0 10px">
      The same question answered by <code>POST /api/fulfillment/quote</code>: inventory first, then purchase orders in arrival order,
      each PO contributing only what is still needed. Read from one repeatable-read snapshot with <code>java.sql</code> directly.
    </p>
    <div class="stats">
      <div class="stat"><span class="muted">Requested</span><b>{{ fulfillment.requestedQuantity }}</b></div>
      <div class="stat"><span class="muted">From inventory</span><b>{{ fulfillment.allocatedFromInventory }}</b></div>
      <div class="stat"><span class="muted">Fulfilled</span><b>{{ fulfillment.fulfilledQuantity }}</b></div>
      <div class="stat"><span class="muted">Shortfall</span><b :class="{ error: fulfillment.remainingQuantity > 0 }">{{ fulfillment.remainingQuantity }}</b></div>
      <div class="stat"><span class="muted">Fully fulfilled by</span><b>{{ fulfillment.fullyFulfilled ? (fulfillment.fulfilledBy ?? 'now') : 'no' }}</b></div>
    </div>
    <div class="table-wrap" v-if="fulfillment.purchaseOrderAllocations.length">
      <table>
        <thead><tr><th>PO</th><th class="num">Allocated</th><th>Expected at FC</th><th>Confidence</th></tr></thead>
        <tbody>
          <tr v-for="a in fulfillment.purchaseOrderAllocations" :key="a.purchaseOrderId">
            <td>{{ a.poNumber }}</td><td class="num">{{ a.allocatedQuantity }}</td><td>{{ a.expectedAt }}</td><td><ConfidencePill :value="a.confidence" /></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section class="panel" v-if="config?.availabilityUrl" style="margin-top: 16px">
    <h2>Storefront projection (DynamoDB via Lambda)</h2>
    <p class="note" style="margin: 0 0 10px">
      What a storefront would read: a DynamoDB copy of this SKU's availability, refreshed by a Lambda whenever a
      container's predicted arrival changes (<code>shipment.eta-updated</code> → SDK invoke → conditional put on
      <code>updatedAt</code>). This panel calls the Lambda Function URL directly — the operational database is never on that path.
    </p>
    <div class="controls" style="margin-bottom: 8px">
      <button @click="loadStorefront">Re-read</button>
      <button @click="projectAll" :disabled="projecting">{{ projecting ? 'Projecting…' : 'Re-project all SKUs' }}</button>
      <span v-if="storefront" class="muted">last write {{ storefront.updatedAt.slice(0, 19).replace('T', ' ') }}</span>
      <span v-if="storefrontError" class="error">{{ storefrontError }}</span>
    </div>
    <div class="table-wrap" v-if="storefront">
      <table>
        <thead><tr><th>FC</th><th class="num">Available now</th><th>Promise (qty 1)</th><th>Next arrival</th><th>Conf.</th><th>Source</th></tr></thead>
        <tbody>
          <tr v-for="r in storefront.byFc" :key="r.fc">
            <td><b>{{ r.fc }}</b> <span class="muted">{{ r.fcName }}</span></td>
            <td class="num">{{ r.availableNow }}</td>
            <td>{{ r.promisable ? r.promiseDate : '—' }}</td>
            <td>{{ r.nextArrival ?? '—' }}</td>
            <td><ConfidencePill :value="r.confidence" /></td>
            <td class="muted">{{ r.source }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
