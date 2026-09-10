package com.haoyu.inbound.projection;

import com.haoyu.inbound.common.AppProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProjectionController {

    private final AvailabilityProjectionService projection;
    private final AppProperties props;

    ProjectionController(AvailabilityProjectionService projection, AppProperties props) {
        this.projection = projection;
        this.props = props;
    }

    /** Re-projects every SKU x FC; run after a reseed or a dbt refresh. */
    @PostMapping("/api/availability/project-all")
    AvailabilityProjectionService.Summary projectAll() {
        return projection.projectAll();
    }

    /** Runtime config for the web app: where the storefront read endpoint lives (empty when not deployed). */
    @GetMapping("/api/config")
    Map<String, Object> config() {
        return Map.of(
                "availabilityUrl", props.aws().availabilityUrl() == null ? "" : props.aws().availabilityUrl(),
                "projectionEnabled", props.aws().projectionEnabled());
    }
}
