package com.claimsflow.dashboard;

import java.time.Instant;

/**
 * Snapshot of live claim-processing metrics pushed to {@code /topic/metrics}
 * and served at {@code GET /api/v1/dashboard/metrics}.
 */
public record ClaimDashboardMetrics(
        long totalClaims,
        long submitted,
        long underReview,
        long adjudication,
        long approved,
        long denied,
        double approvalRate,        // approved / (approved + denied); 0 when no terminal claims
        long fraudFlaggedCount,     // fraudScore >= threshold
        double averageFraudScore,   // portfolio-wide; 0 when nothing scored
        Instant generatedAt
) {}
