package com.claimsflow.claims.domain.fraud;

import java.util.List;

/**
 * Aggregated result of running the entire fraud indicator chain on a claim.
 */
public record FraudScoreResult(int totalScore, List<FraudIndicatorResult> indicatorResults) {

    public FraudScoreResult {
        if (totalScore < 0 || totalScore > 100) {
            throw new IllegalArgumentException("totalScore must be in 0..100");
        }
        indicatorResults = List.copyOf(indicatorResults);
    }

    public boolean exceeds(int threshold) {
        return totalScore >= threshold;
    }
}
