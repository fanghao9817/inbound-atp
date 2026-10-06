package com.haoyu.inbound.planning;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlanningController {

    private final ReplenishmentService replenishment;

    PlanningController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    /** Every SKU x FC with its weeks of cover, lowest first; suggestedQty > 0 means "order now". */
    @GetMapping("/api/planning/replenishment")
    List<ReplenishmentService.Suggestion> replenishment(@RequestParam(defaultValue = "8") double reorderWeeks,
                                                       @RequestParam(defaultValue = "14") double targetWeeks) {
        return replenishment.suggest(reorderWeeks, targetWeeks);
    }
}
