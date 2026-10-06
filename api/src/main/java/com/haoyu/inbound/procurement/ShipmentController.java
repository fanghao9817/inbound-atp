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
import org.springframework.web.bind.annotation.RestController;

@RestController
class ShipmentController {

    private final ShipmentRepository shipments;
    private final ShipmentService service;
    private final ReceivingService receiving;

    ShipmentController(ShipmentRepository shipments, ShipmentService service, ReceivingService receiving) {
        this.shipments = shipments;
        this.service = service;
        this.receiving = receiving;
    }

    record ShipmentView(Shipment shipment, ShipmentRepository.LaneOf lane, List<ShipmentMilestone> milestones) {}

    @GetMapping("/api/shipments/{id}")
    ShipmentView get(@PathVariable long id) {
        return new ShipmentView(shipments.require(id), shipments.laneOf(id), shipments.milestones(id));
    }

    record MilestoneRequest(@NotNull MilestoneType type, @NotNull OffsetDateTime occurredAt, @NotNull MilestoneSource source,
                            @NotNull UUID eventId) {}

    record MilestoneResponse(long shipmentId, UUID eventId, boolean accepted, String note) {}

    /**
     * Carrier / customs / FC gate-in feed. 202 because the ETA is recalculated asynchronously by the
     * Kafka consumer; a replayed event is acknowledged with accepted=false and changes nothing.
     */
    @PostMapping("/api/internal/shipments/{id}/milestones")
    ResponseEntity<MilestoneResponse> milestone(@PathVariable long id, @Valid @RequestBody MilestoneRequest req) {
        var outcome = service.recordMilestone(id, req.type(), req.occurredAt(), req.source(), req.eventId());
        boolean accepted = outcome == ShipmentService.Outcome.RECORDED;
        String note = accepted ? "recorded; ETA recalculation queued" : "already recorded; ignored";
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MilestoneResponse(id, req.eventId(), accepted, note));
    }

    record ReceiptRequest(@NotNull UUID eventId, @NotNull OffsetDateTime receivedAt, List<ReceivedLine> lines) {}

    /** Warehouse putaway: 201 when posted, 200 when this event id was already posted. */
    @PostMapping("/api/internal/shipments/{id}/receipt")
    ResponseEntity<ReceivingService.Receipt> receipt(@PathVariable long id, @Valid @RequestBody ReceiptRequest req) {
        var receipt = receiving.receive(id, req.eventId(), req.receivedAt(), req.lines());
        return ResponseEntity.status(receipt.outcome() == ReceivingService.Outcome.POSTED ? HttpStatus.CREATED : HttpStatus.OK).body(receipt);
    }
}
