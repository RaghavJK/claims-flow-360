package com.claimsflow.dashboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes a fresh metrics snapshot to all {@code /topic/metrics} subscribers.
 *
 * <p>Triggered from two places: the SQS consumer after each processed claim
 * event (near-real-time), and {@link DashboardScheduler} as a heartbeat so
 * dashboards converge even when no events flow.
 *
 * <p>A broadcast failure must never break event processing — it is logged
 * and swallowed. Losing one dashboard frame is harmless; losing a projection
 * update is not.
 */
@Slf4j
@Component
public class DashboardBroadcaster {

    public static final String METRICS_TOPIC = "/topic/metrics";

    private final SimpMessagingTemplate messagingTemplate;
    private final DashboardMetricsService metricsService;

    public DashboardBroadcaster(SimpMessagingTemplate messagingTemplate,
                                DashboardMetricsService metricsService) {
        this.messagingTemplate = messagingTemplate;
        this.metricsService = metricsService;
    }

    public void broadcast() {
        try {
            ClaimDashboardMetrics metrics = metricsService.snapshot();
            messagingTemplate.convertAndSend(METRICS_TOPIC, metrics);
            log.debug("Dashboard metrics broadcast: total={} approvalRate={}",
                    metrics.totalClaims(), metrics.approvalRate());
        } catch (RuntimeException ex) {
            log.warn("Dashboard broadcast failed (non-fatal): {}", ex.getMessage());
        }
    }
}
