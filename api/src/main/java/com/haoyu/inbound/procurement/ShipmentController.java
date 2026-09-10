package com.haoyu.inbound.procurement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
class ShipmentController {

    private final ShipmentRepository shipments;
    private final ShipmentService service;

    ShipmentController(ShipmentRepository shipments, ShipmentService service) {
        this.shipments = shipments;
        this.service = service;
    }

    record ShipmentView(Shipment shipment, ShipmentRepository.LaneOf lane, List<ShipmentMilestone> milestones) {}

    @GetMapping("/{id}")
    ShipmentView get(@PathVariable long id) {
        return new ShipmentView(shipments.require(id), shipments.laneOf(id), shipments.milestones(id));
    }

    record MilestoneRequest(@NotNull MilestoneType type, @NotNull OffsetDateTime occurredAt, String source, UUID eventId) {}

    record MilestoneResponse(long shipmentId, UUID eventId, boolean accepted, String note) {}

    /**
     * Ingests a milestone. 202 because the ETA is recalculated asynchronously by the Kafka consumer;
     * a replayed eventId is acknowledged but not re-processed.
     */
    @PostMapping("/{id}/milestones")
    ResponseEntity<MilestoneResponse> milestone(@PathVariable long id, @Valid @RequestBody MilestoneRequest req) {
        UUID eventId = req.eventId() != null ? req.eventId() : UUID.randomUUID();
        String source = req.source() != null ? req.source() : "MANUAL";
        boolean accepted = service.recordMilestone(id, req.type(), req.occurredAt(), source, eventId);
        String note = accepted ? "recorded; ETA recalculation queued" : "duplicate event or stage already recorded; ignored";
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MilestoneResponse(id, eventId, accepted, note));
    }
}
