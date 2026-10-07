<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { Activity, Day, Kpis } from '@/api/types'
import { usePolling } from '@/composables/usePolling'
import { ago, delta, pct, ptClock, ptTime, sparkline, type Delta } from '@/lib/format'

const kpis = ref<Kpis | null>(null)
const days = ref<Day[]>([])
const feed = ref<Activity[]>([])
const fresh = ref(new Set<string>())
const error = ref<string | null>(null)

const key = (a: Activity) => a.eventKey

async function loadKpis() {
  try {
    ;[kpis.value, days.value] = await Promise.all([api.kpis(), api.daily(28)])
    error.value = null
  } catch (e) {
    error.value = String(e)
  }
}

async function loadFeed() {
  try {
    const next = await api.activity(40)
    const seen = new Set(feed.value.map(key))
    fresh.value = feed.value.length ? new Set(next.filter((a) => !seen.has(key(a))).map(key)) : new Set()
    feed.value = next
  } catch {
    /* the KPI poll reports errors */
  }
}

usePolling(loadKpis, 30_000)
usePolling(loadFeed, 10_000)

/** Start of today / this week / last week in Vancouver time, to tell whether a comparison period predates the data. */
const periods = computed(() => {
  if (!kpis.value) return null
  const asOf = new Date(kpis.value.asOf)
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', { timeZone: 'America/Vancouver', weekday: 'short', hour: 'numeric', minute: 'numeric', hour12: false })
      .formatToParts(asOf).map((p) => [p.type, p.value]),
  )
  const minutesIntoDay = (Number(parts.hour) % 24) * 60 + Number(parts.minute)
  const dayStart = asOf.getTime() - minutesIntoDay * 60_000
  const weekday = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(parts.weekday ?? 'Mon')
  const weekStart = dayStart - weekday * 86_400_000
  return { yesterdayStart: dayStart - 86_400_000, lastWeekStart: weekStart - 7 * 86_400_000 }
})

function before(start?: number): boolean {
  const since = kpis.value?.historySince
  return !since || start == null || new Date(since).getTime() > start
}

interface Tile { label: string; value: string; delta?: Delta; vs?: string; sub?: string; tone?: 'ok' | 'warn' | 'bad' }

const tiles = computed<Tile[]>(() => {
  const k = kpis.value
  const p = periods.value
  if (!k || !p) return []
  const noYesterday = before(p.yesterdayStart)
  const noLastWeek = before(p.lastWeekStart)
  const fullWeek = !before(new Date(k.asOf).getTime() - 7 * 86_400_000)   // judge 7-day rates only on 7 days of data
  const served = k.onTimeShare7d
  return [
    { label: 'Orders today', value: String(k.ordersToday), delta: delta(k.ordersToday, k.ordersYesterdaySameTime, noYesterday),
      vs: 'vs yesterday by now', sub: `${k.unitsOrderedToday} units` },
    { label: 'Units shipped today', value: String(k.unitsShippedToday),
      delta: delta(k.unitsShippedToday, k.unitsShippedYesterdaySameTime, noYesterday), vs: 'vs yesterday by now',
      sub: `${k.awaitingShipment} orders waiting to be picked` },
    { label: 'Orders this week', value: String(k.ordersWtd), delta: delta(k.ordersWtd, k.ordersLastWtd, noLastWeek),
      vs: 'vs last week by now', sub: `${k.unitsShippedWtd} units shipped` },
    { label: 'Containers in this week', value: String(k.containersGatedInWtd),
      delta: delta(k.containersGatedInWtd, k.containersGatedInLastWtd, noLastWeek), vs: 'vs last week by now',
      sub: `${k.unitsReceivedWtd} units put away` },
    { label: fullWeek ? 'Promised on time, 7 days' : 'Promised on time, since go-live', value: pct(served),
      tone: served == null || !fullWeek ? undefined : served >= 0.85 ? 'ok' : served >= 0.7 ? 'warn' : 'bad',
      sub: `online from stock, B2B by its date · ${k.unitsRejected7d} units lost (no date could be promised)` },
    { label: 'Open backorders', value: String(k.openBackorders), tone: k.lateBackorders > 0 ? 'warn' : undefined,
      sub: `${k.lateBackorders} past their promise date · ${k.repromisedToday} re-promised today` },
    { label: 'Containers on the way', value: String(k.openContainers), tone: k.overdueContainers > 0 ? 'warn' : undefined,
      sub: `${k.lateContainers} late vs plan · ${k.overdueContainers} overdue · ${k.etaChangesToday} ETA changes today` },
    { label: 'P80 arrivals on time, 28 days', value: k.predictionsScored28d ? pct(k.p80HitRate28d) : 'n/a',
      tone: !k.predictionsScored28d || k.p80HitRate28d == null ? undefined : k.p80HitRate28d >= 0.7 ? 'ok' : 'warn',
      sub: k.predictionsScored28d
        ? `${k.predictionsScored28d} containers scored · mean error ${k.meanAbsErrorDays28d ?? '—'} days`
        : 'scored against the prediction made 14 days before arrival; needs live history' },
  ]
})

const health = computed(() => {
  const k = kpis.value
  if (!k) return null
  const orderStale = k.minutesSinceLastOrder == null || k.minutesSinceLastOrder > 180
  const milestoneStale = k.minutesSinceLastMilestone == null || k.minutesSinceLastMilestone > 36 * 60
  const outboxStale = (k.outboxOldestSeconds ?? 0) > 300
  return {
    tone: outboxStale || orderStale ? 'bad' : milestoneStale ? 'warn' : 'ok',
    rows: [
      { label: 'Last customer order', value: ago(k.minutesSinceLastOrder), bad: orderStale },
      { label: 'Last carrier / WMS event', value: ago(k.minutesSinceLastMilestone), bad: milestoneStale },
      { label: 'Events waiting for Kafka', value: k.outboxPending ? `${k.outboxPending} (oldest ${k.outboxOldestSeconds}s)` : '0', bad: outboxStale },
    ],
  }
})

const series = computed(() => [
  { label: 'Units ordered', values: days.value.map((d) => d.unitsOrdered) },
  { label: 'Units shipped', values: days.value.map((d) => d.unitsShipped) },
  { label: 'Units received', values: days.value.map((d) => d.unitsReceived) },
  { label: 'Backorders created', values: days.value.map((d) => d.backordersCreated) },
])
const lastDays = computed(() => days.value.slice(-7))
const weekday = (d: string) => new Date(`${d}T12:00:00Z`).toLocaleDateString('en-CA', { weekday: 'short', timeZone: 'UTC' })
</script>

<template>
  <div class="today-head">
    <div>
      <h1>Today</h1>
      <p class="lede">
        The network right now: customers ordering, the warehouse shipping, containers arriving, predictions moving.
        A simulator plays the outside world around the clock through the same API any client would use.
      </p>
    </div>
    <div class="asof" v-if="kpis"><span class="live-dot" />as of {{ ptClock(kpis.asOf) }} Vancouver time
      <div class="muted" v-if="kpis.historySince">live since {{ ptTime(kpis.historySince) }}</div>
    </div>
  </div>
  <p v-if="error" class="error">{{ error }}</p>

  <div class="tiles">
    <div v-for="t in tiles" :key="t.label" class="tile panel" :class="t.tone">
      <div class="tile-label">{{ t.label }}</div>
      <div class="tile-value">{{ t.value }}
        <span v-if="t.delta" class="delta" :class="t.delta.tone" :title="t.vs">{{ t.delta.text }}</span>
      </div>
      <div class="tile-sub muted">{{ t.sub }}<template v-if="t.delta && t.vs"> · {{ t.vs }}</template></div>
    </div>
  </div>

  <div class="grid cols-2" style="margin-top: 16px">
    <section class="panel">
      <h2>Last 28 days <span class="muted" style="font-weight: normal">(business days, Vancouver time)</span></h2>
      <div class="sparks">
        <div v-for="s in series" :key="s.label" class="spark">
          <div class="spark-head"><span>{{ s.label }}</span><b>{{ s.values.at(-1) ?? 0 }}</b><span class="muted">today</span></div>
          <svg viewBox="0 0 280 44" preserveAspectRatio="none" role="img" :aria-label="`${s.label}, last 28 days`">
            <polyline :points="sparkline(s.values, 280, 44)" fill="none" stroke="currentColor" stroke-width="1.6" />
          </svg>
        </div>
      </div>
      <div class="table-wrap" style="margin-top: 10px">
        <table>
          <thead><tr><th>Day</th><th class="num">Orders</th><th class="num">Units</th><th class="num">Shipped</th><th class="num">Received</th><th class="num">Backorders</th><th class="num">Lost</th></tr></thead>
          <tbody>
            <tr v-for="d in [...lastDays].reverse()" :key="d.day">
              <td>{{ weekday(d.day) }} {{ d.day.slice(5) }}</td><td class="num">{{ d.orders }}</td><td class="num">{{ d.unitsOrdered }}</td>
              <td class="num">{{ d.unitsShipped }}</td><td class="num">{{ d.unitsReceived }}</td><td class="num">{{ d.backordersCreated }}</td>
              <td class="num" :class="{ error: d.rejected > 0 }">{{ d.rejected }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="note">Sundays the warehouse is closed, so Monday ships the backlog; evenings and Sundays are the busiest shopping hours.</p>
    </section>

    <section class="panel">
      <div class="feed-head">
        <h2>Activity</h2>
        <span v-if="health" class="pill" :class="health.tone">{{ health.tone === 'ok' ? 'feeds healthy' : health.tone === 'warn' ? 'feeds quiet' : 'feeds stalled' }}</span>
      </div>
      <div v-if="health" class="health">
        <div v-for="r in health.rows" :key="r.label"><span class="muted">{{ r.label }}</span> <b :class="{ error: r.bad }">{{ r.value }}</b></div>
      </div>
      <ul class="feed">
        <li v-for="a in feed" :key="key(a)" :class="{ fresh: fresh.has(key(a)) }">
          <span class="feed-time">{{ ptClock(a.at) }}</span>
          <span class="pill kind" :class="a.kind">{{ a.kind }}</span>
          <span class="feed-title">{{ a.title }}<span v-if="a.detail" class="muted"> · {{ a.detail }}</span></span>
        </li>
        <li v-if="!feed.length" class="muted">Nothing yet.</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.today-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
.today-head > div:first-child { flex: 1 1 0; min-width: 280px; }
.asof { text-align: right; font-size: 13px; white-space: nowrap; }
.live-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: var(--ok); margin-right: 6px; animation: pulse 2s infinite; }
@keyframes pulse { 50% { opacity: 0.3; } }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 12px; }
.tile { padding: 12px 14px; border-left: 4px solid var(--line); }
.tile.ok { border-left-color: var(--ok); }
.tile.warn { border-left-color: var(--warn); }
.tile.bad { border-left-color: var(--bad); }
.tile-label { font-size: 12px; color: var(--muted); }
.tile-value { font-size: 26px; font-weight: 650; margin: 2px 0; display: flex; align-items: baseline; gap: 8px; }
.tile-sub { font-size: 12px; }
.delta { font-size: 13px; font-weight: 600; }
.delta.up { color: var(--ok); }
.delta.down { color: var(--bad); }
.delta.flat, .delta.na { color: var(--muted); font-weight: 500; }
.sparks { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 16px; }
.spark { color: var(--accent); }
.spark-head { display: flex; gap: 6px; align-items: baseline; color: var(--ink); font-size: 12px; }
.spark svg { width: 100%; height: 44px; display: block; }
.feed-head { display: flex; justify-content: space-between; align-items: center; }
.health { display: grid; gap: 2px; font-size: 12px; margin-bottom: 10px; }
.feed { list-style: none; margin: 0; padding: 0; max-height: 640px; overflow-y: auto; }
.feed li { display: flex; gap: 8px; align-items: baseline; padding: 5px 2px; border-bottom: 1px solid var(--line); font-size: 13px; }
.feed li.fresh { animation: flash 3s ease-out; }
@keyframes flash { from { background: #fef9c3; } to { background: transparent; } }
.feed-time { color: var(--muted); font-variant-numeric: tabular-nums; min-width: 40px; }
.feed-title { min-width: 0; overflow-wrap: anywhere; }
.pill.kind { font-size: 10px; min-width: 64px; text-align: center; }
.pill.kind.ORDER { background: #dbeafe; color: #1e40af; }
.pill.kind.SHIPPED { background: #dcfce7; color: #166534; }
.pill.kind.RECEIPT { background: #d1fae5; color: #065f46; }
.pill.kind.MILESTONE { background: #f3f4f6; color: #374151; }
.pill.kind.ETA { background: #ede9fe; color: #5b21b6; }
.pill.kind.PO { background: #fef3c7; color: #92400e; }
.pill.kind.PORT, .pill.kind.PROMISE { background: #fee2e2; color: #991b1b; }
</style>
