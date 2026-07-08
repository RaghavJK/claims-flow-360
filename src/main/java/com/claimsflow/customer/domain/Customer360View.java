package com.claimsflow.customer.domain;

import com.claimsflow.claims.domain.ClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregated policyholder view (FR-05) — the Customer360.
 *
 * <p>Read-only projection assembled from the claims write model. Financial
 * totals are computed from MySQL (the authoritative store) rather than the
 * OpenSearch read model: money aggregates in insurance must be strongly
 * consistent, and a per-policyholder claim list is small enough that the
 * relational query is cheap. OpenSearch remains the engine for portfolio-wide
 * search and analytics, where eventual consistency is acceptable.
 */
public record Customer360View(
        String policyNumber,
        long totalClaims,
        Map<ClaimStatus, Long> claimsByStatus,
        BigDecimal totalAmountClaimed,
        BigDecimal totalAmountApproved,
        long fraudFlaggedCount,
        double averageFraudScore,
        Instant firstClaimAt,
        Instant lastClaimAt,
        List<ClaimSnapshot> recentClaims
) {

    /** Compact per-claim line item for the recent-activity strip. */
    public record ClaimSnapshot(
            String claimRef,
            ClaimStatus status,
            BigDecimal amountClaimed,
            Integer fraudScore,
            Instant createdAt
    ) {}
}
