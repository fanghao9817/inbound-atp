package com.haoyu.inbound.planning;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlanningController {

    private final ReplenishmentService replenishment;

    PlanningController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    /** Every SKU x FC with demand, lead time, order-up-to level and position, plus the POs a buyer should place. */
    @GetMapping("/api/planning/replenishment")
    ReplenishmentService.Plan replenishment() {
        return replenishment.plan();
    }
}
