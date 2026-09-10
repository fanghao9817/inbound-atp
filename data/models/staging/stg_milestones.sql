-- One row per milestone with its lane attached. The raw table is an append-only event log.
select
    m.id                          as milestone_id,
    m.shipment_id,
    po.origin_port,
    fc.code                       as dest_fc_code,
    m.type                        as stage,
    m.occurred_at,
    m.source
from {{ src('shipment_milestone') }} m
join {{ src('shipment') }} s        on s.id = m.shipment_id
join {{ src('purchase_order') }} po on po.id = s.po_id
join {{ src('fulfillment_center') }} fc on fc.id = po.dest_fc_id
