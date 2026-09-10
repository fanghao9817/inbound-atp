package com.haoyu.inbound.atp;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/atp")
class AtpController {

    private final AtpService atp;

    AtpController(AtpService atp) {
        this.atp = atp;
    }

    /** e.g. GET /api/atp?sku=SOFA-3S-OAT&fc=FC-RIC&qty=5 */
    @GetMapping
    AtpService.AtpQuote quote(@RequestParam String sku, @RequestParam String fc, @RequestParam(defaultValue = "1") int qty) {
        return atp.quote(sku, fc, qty);
    }

    /** e.g. GET /api/atp/SOFA-3S-OAT?qty=5 — one row per fulfillment center */
    @GetMapping("/{sku}")
    List<AtpService.FcSummary> summary(@PathVariable String sku, @RequestParam(defaultValue = "1") int qty) {
        return atp.summary(sku, qty);
    }
}
