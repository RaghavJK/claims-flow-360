package com.claimsflow.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST fallback for the dashboard metrics — same snapshot the WebSocket
 * pushes, for clients that poll instead of subscribing.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardMetricsService metricsService;

    public DashboardController(DashboardMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public ClaimDashboardMetrics metrics() {
        return metricsService.snapshot();
    }
}
