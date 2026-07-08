package com.claimsflow.dashboard;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat broadcast so dashboards stay current even when no claim events
 * are flowing (the SQS consumer covers the event-driven path).
 */
@Component
@Profile("!test")
public class DashboardScheduler {

    private final DashboardBroadcaster broadcaster;

    public DashboardScheduler(DashboardBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Scheduled(fixedDelayString = "${claimsflow.dashboard.broadcast-interval-ms:10000}")
    public void heartbeat() {
        broadcaster.broadcast();
    }
}
