// Mirrors the API records in api/src/main/java/com/haoyu/inbound/**

export interface Sku { id: number; code: string; name: string; category: string }
export interface FulfillmentCenter { id: number; code: string; name: string; region: string; receivingBufferDays: number }

export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH'
export type Stage = 'BOOKED' | 'DEPARTED_ORIGIN' | 'ARRIVED_DEST_PORT' | 'CUSTOMS_CLEARED' | 'RECEIVED_FC'
export const STAGES: Stage[] = ['BOOKED', 'DEPARTED_ORIGIN', 'ARRIVED_DEST_PORT', 'CUSTOMS_CLEARED', 'RECEIVED_FC']

export interface TimelinePoint { date: string; projected: number; atp: number }
export interface SupplyLine { poNumber: string; qty: number; arrives: string; stage: Stage; confidence?: Confidence }
export interface DemandLine { reference: string; qty: number; needBy: string }
export interface AtpQuote {
  sku: string; fc: string; qty: number; availableNow: number; promiseDate?: string; promisable: boolean
  horizonDays: number; timeline: TimelinePoint[]; supplies: SupplyLine[]; demands: DemandLine[]
}
export interface FcSummary { fc: string; fcName: string; availableNow: number; promiseDate?: string; promisable: boolean }

export interface FulfillmentRequest { sku: string; fc?: string; requestedQuantity: number }
export interface PurchaseOrderAllocation { purchaseOrderId: number; poNumber: string; allocatedQuantity: number; expectedAt: string; confidence?: Confidence }
export interface FulfillmentResponse {
  sku: string; fc?: string; requestedQuantity: number; fulfilledQuantity: number; remainingQuantity: number
  allocatedFromInventory: number; purchaseOrderAllocations: PurchaseOrderAllocation[]; fullyFulfilled: boolean; fulfilledBy?: string
}

export interface LineView { skuCode: string; skuName: string; qtyOrdered: number; qtyReceived: number }
export interface PurchaseOrderView {
  id: number; poNumber: string; supplier: string; originPort: string; destFc: string; status: string; plannedArrival: string
  shipmentId?: number; carrier?: string; containerNo?: string; currentStage?: Stage; predictedArrival?: string
  predictedConfidence?: Confidence; predictionBasis?: string; daysLate?: number; lines: LineView[]
}

export interface Shipment {
  id: number; poId: number; carrier: string; containerNo?: string; plannedDeparture: string; plannedArrival: string
  predictedArrival?: string; predictedConfidence?: Confidence; predictionBasis?: string; currentStage: Stage; version: number
}
export interface Milestone { id: number; shipmentId: number; type: Stage; occurredAt: string; source: string; eventId: string; recordedAt: string }
export interface ShipmentView { shipment: Shipment; lane: { originPort: string; destFcCode: string; receivingBufferDays: number }; milestones: Milestone[] }
export interface MilestoneResponse { shipmentId: number; eventId: string; accepted: boolean; note: string }

export interface LateShipment {
  shipmentId: number; poNumber: string; originPort: string; destFc: string; currentStage: Stage; plannedArrival: string
  predictedArrival: string; daysLate: number; confidence: Confidence; basis: string; commitmentsDueBeforeArrival: number
}
export interface LaneStats { originPort: string; destFcCode: string; fromStage: Stage; toStage: Stage; p50Days: number; p80Days: number; sampleN: number }
export interface RecalcSummary { shipments: number; changed: number }
