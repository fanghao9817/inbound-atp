import type {
  AppConfig,
  AtpQuote, FcSummary, FulfillmentCenter, FulfillmentRequest, FulfillmentResponse, LaneStats, LateShipment,
  MilestoneResponse, PurchaseOrderView, RecalcSummary, ShipmentView, Sku, Stage, StorefrontAvailability,
} from './types'

export class ApiError extends Error {
  constructor(public status: number, public detail: string) {
    super(`${status}: ${detail}`)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init })
  if (!res.ok) {
    let detail = res.statusText
    try { detail = (await res.json()).detail ?? detail } catch { /* not a problem+json body */ }
    throw new ApiError(res.status, detail)
  }
  return (await res.json()) as T
}

const q = (params: Record<string, string | number | undefined>) =>
  new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)])).toString()

export const api = {
  skus: () => request<Sku[]>('/api/skus'),
  fulfillmentCenters: () => request<FulfillmentCenter[]>('/api/fulfillment-centers'),
  atp: (sku: string, fc: string, qty: number) => request<AtpQuote>(`/api/atp?${q({ sku, fc, qty })}`),
  atpSummary: (sku: string, qty: number) => request<FcSummary[]>(`/api/atp/${encodeURIComponent(sku)}?${q({ qty })}`),
  fulfillmentQuote: (body: FulfillmentRequest) =>
    request<FulfillmentResponse>('/api/fulfillment/quote', { method: 'POST', body: JSON.stringify(body) }),
  purchaseOrders: (status = 'OPEN') => request<PurchaseOrderView[]>(`/api/purchase-orders?${q({ status })}`),
  shipment: (id: number) => request<ShipmentView>(`/api/shipments/${id}`),
  postMilestone: (id: number, body: { type: Stage; occurredAt: string; source?: string; eventId?: string }) =>
    request<MilestoneResponse>(`/api/shipments/${id}/milestones`, { method: 'POST', body: JSON.stringify(body) }),
  exceptions: () => request<LateShipment[]>('/api/exceptions'),
  laneStats: () => request<LaneStats[]>('/api/lanes/stats'),
  recalculateAll: () => request<RecalcSummary>('/api/eta/recalculate-all', { method: 'POST', body: '{}' }),
  config: () => request<AppConfig>('/api/config'),
  projectAll: () => request<{ items: number }>('/api/availability/project-all', { method: 'POST', body: '{}' }),
  /** Straight to the Lambda Function URL — the storefront never touches the operational database. */
  storefront: (baseUrl: string, sku: string) =>
    request<StorefrontAvailability>(`${baseUrl.replace(/\/$/, '')}/?${q({ sku })}`),
}
