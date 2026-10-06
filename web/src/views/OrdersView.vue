<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { OrderView } from '@/api/types'
import { usePolling } from '@/composables/usePolling'
import { ptClock, ptTime } from '@/lib/format'
import { useCatalogStore } from '@/stores/catalog'

const catalog = useCatalogStore()
const rows = ref<OrderView[]>([])
const status = ref('')
const channel = ref('')
const loading = ref(false)
const error = ref<string | null>(null)

const form = ref({ sku: 'SOFA-3S-OAT', fc: 'FC-RIC', qty: 1 })
const placing = ref(false)
const placed = ref<OrderView | null>(null)
const placeError = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    rows.value = await api.orders(status.value || undefined, channel.value || undefined, 100)
    error.value = null
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}
const { refresh } = usePolling(load, 15_000)
onMounted(() => catalog.load())

async function place() {
  placing.value = true
  placed.value = null
  placeError.value = null
  try {
    placed.value = await api.placeVisitorOrder(form.value)
    await refresh()
  } catch (e) {
    placeError.value = e instanceof ApiError ? (e.status === 429 ? 'Too many orders right now - try again in a minute.' : e.detail) : String(e)
  } finally {
    placing.value = false
  }
}

const outcome = computed(() => {
  const o = placed.value
  if (!o) return ''
  switch (o.status) {
    case 'RESERVED': return `Reserved from stock at ${o.fc}: ships within a day.`
    case 'BACKORDERED': return `Nothing free at ${o.fc} today, so it was promised against an inbound container for ${o.promiseDate}. If that container slips, the promise moves and the change shows on the Today page.`
    case 'REJECTED': return `No date can be promised at ${o.fc} within the 120-day horizon: recorded as lost demand.`
    default: return o.status
  }
})

const pillTone: Record<string, string> = { RESERVED: 'ok', SHIPPED: 'ok', SCHEDULED: '', BACKORDERED: 'warn', CANCELLED: '', REJECTED: 'bad' }
</script>

<template>
  <h1>Orders</h1>
  <p class="lede">
    Customer demand as it arrives. Each order is decided in one transaction with the stock row locked: reserve what is
    on hand, otherwise promise it against the next inbound container (backorder), otherwise reject it. B2B orders for a
    later date are scheduled and reserved two days before they are due.
  </p>

  <section class="panel" style="margin-bottom: 16px">
    <h2>Try it: place an order</h2>
    <div class="controls" style="margin-bottom: 6px">
      <label class="field">SKU
        <select v-model="form.sku"><option v-for="s in catalog.skus" :key="s.code" :value="s.code">{{ s.code }} — {{ s.name }}</option></select>
      </label>
      <label class="field">FC
        <select v-model="form.fc"><option v-for="f in catalog.fcs" :key="f.code" :value="f.code">{{ f.code }} — {{ f.name }}</option></select>
      </label>
      <label class="field">Qty
        <input v-model.number="form.qty" type="number" min="1" max="5" />
      </label>
      <button class="primary" @click="place" :disabled="placing">{{ placing ? 'Placing…' : 'Place order' }}</button>
    </div>
    <p v-if="placed" class="note"><b>{{ placed.orderRef }}</b>: <span class="pill" :class="pillTone[placed.status]">{{ placed.status }}</span> {{ outcome }}</p>
    <p v-else-if="placeError" class="error">{{ placeError }}</p>
    <p v-else class="note">Up to 5 units. Visitor orders go through the same decision as every other order, are left out of the KPIs, and are cancelled after an hour so the stock goes back.</p>
  </section>

  <div class="controls">
    <label class="field">Status
      <select v-model="status" @change="refresh">
        <option value="">all</option><option>RESERVED</option><option>SCHEDULED</option><option>BACKORDERED</option>
        <option>SHIPPED</option><option>CANCELLED</option><option>REJECTED</option>
      </select>
    </label>
    <label class="field">Channel
      <select v-model="channel" @change="refresh"><option value="">all</option><option>ONLINE</option><option>B2B</option><option>STORE</option></select>
    </label>
    <span class="muted">latest {{ rows.length }} · updates every 15 s</span>
    <span v-if="error" class="error">{{ error }}</span>
  </div>
  <section class="panel table-wrap">
    <table>
      <thead><tr><th>Placed</th><th>Order</th><th>Channel</th><th>SKU</th><th>FC</th><th class="num">Qty</th><th>Status</th><th>Promised</th><th>Need by</th><th>Shipped</th></tr></thead>
      <tbody>
        <tr v-for="o in rows" :key="o.id">
          <td :title="ptTime(o.createdAt)">{{ ptTime(o.createdAt).replace(' PT', '') }}</td>
          <td><b>{{ o.orderRef }}</b> <span v-if="o.origin !== 'FEED'" class="muted" style="font-size: 11px">{{ o.origin.toLowerCase() }}</span></td>
          <td>{{ o.channel }}</td><td>{{ o.sku }}</td><td>{{ o.fc }}</td><td class="num">{{ o.qty }}</td>
          <td><span class="pill" :class="pillTone[o.status]">{{ o.status }}</span></td>
          <td>{{ o.promiseDate ?? '—' }}<span v-if="o.firstPromiseDate && o.promiseDate && o.promiseDate !== o.firstPromiseDate" class="error" :title="`first promised ${o.firstPromiseDate}`"> (moved)</span></td>
          <td>{{ o.needBy ?? '—' }}</td>
          <td>{{ o.shippedAt ? ptClock(o.shippedAt) : '—' }}</td>
        </tr>
        <tr v-if="!rows.length && !loading"><td colspan="10" class="muted">No orders.</td></tr>
      </tbody>
    </table>
  </section>
</template>
