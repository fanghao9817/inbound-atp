package com.haoyu.inbound.dashboard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private final DashboardRepository dashboard;

    DashboardController(DashboardRepository dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/dashboard/kpis")
    DashboardRepository.Kpis kpis() {
        return dashboard.kpis();
    }

    @GetMapping("/api/dashboard/daily")
    List<DashboardRepository.Day> daily(@RequestParam(defaultValue = "28") int days) {
        return dashboard.daily(Math.min(Math.max(days, 1), 90));
    }

    @GetMapping("/api/activity")
    List<DashboardRepository.Activity> activity(@RequestParam(defaultValue = "40") int limit) {
        return dashboard.activity(Math.min(Math.max(limit, 1), 200));
    }

    record NoteRequest(@NotBlank @Size(max = 80) String ref, @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z_]{2,20}") String kind,
                       @NotBlank @Size(max = 300) String message) {}

    /** Operations notes (e.g. port congestion announced by the carrier feed); idempotent on ref. */
    @PostMapping("/api/internal/notes")
    @ResponseStatus(HttpStatus.CREATED)
    void note(@Valid @RequestBody NoteRequest r) {
        dashboard.addNote(r.ref(), r.kind(), r.message());
    }
}
