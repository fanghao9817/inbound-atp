/** Business time is Vancouver: every timestamp on the page is shown in Pacific time, whatever the viewer's zone. */
export const BUSINESS_ZONE = 'America/Vancouver'

const timeFmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_ZONE, month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
})
const clockFmt = new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_ZONE, hour: '2-digit', minute: '2-digit', hour12: false })

/** "Oct 6, 14:05 PT" */
export function ptTime(iso?: string | null): string {
  if (!iso) return '—'
  return `${timeFmt.format(new Date(iso))} PT`
}

/** "14:05" in Pacific time */
export function ptClock(iso?: string | null): string {
  return iso ? clockFmt.format(new Date(iso)) : '—'
}

/** "just now", "12 min ago", "3 h ago", "2 d ago" */
export function ago(minutes?: number | null): string {
  if (minutes == null) return 'never'
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${Math.round(minutes)} min ago`
  if (minutes < 48 * 60) return `${Math.round(minutes / 60)} h ago`
  return `${Math.round(minutes / 1440)} d ago`
}

export function minutesSince(iso: string, now: Date = new Date()): number {
  return (now.getTime() - new Date(iso).getTime()) / 60000
}

export type Delta = { text: string; tone: 'up' | 'down' | 'flat' | 'na' }

/**
 * Change against the comparison period. `na` when the comparison period falls before the data begins
 * (right after a reset there is no "last week" yet), rather than a misleading +100%.
 */
export function delta(current: number, previous: number, previousIsBeforeHistory = false): Delta {
  if (previousIsBeforeHistory) return { text: 'n/a', tone: 'na' }
  if (previous === 0) return current === 0 ? { text: '±0', tone: 'flat' } : { text: `+${current}`, tone: 'up' }
  const pct = Math.round(((current - previous) / previous) * 100)
  if (pct === 0) return { text: '±0%', tone: 'flat' }
  return { text: `${pct > 0 ? '+' : ''}${pct}%`, tone: pct > 0 ? 'up' : 'down' }
}

export function pct(v?: number | null, digits = 0): string {
  return v == null ? '—' : `${(v * 100).toFixed(digits)}%`
}

/** SVG polyline points for a sparkline of `values` in a width x height box. */
export function sparkline(values: number[], width: number, height: number, pad = 2): string {
  if (!values.length) return ''
  const max = Math.max(...values, 1)
  const step = values.length > 1 ? (width - 2 * pad) / (values.length - 1) : 0
  return values
    .map((v, i) => `${(pad + i * step).toFixed(1)},${(height - pad - (v / max) * (height - 2 * pad)).toFixed(1)}`)
    .join(' ')
}
