package com.claimsflow.claims.domain.fraud;

/**
 * Outcome of a single {@link FraudIndicator} evaluation.
 *
 * @param indicatorName stable identifier of the indicator
 * @param score         contribution (0..100) — higher means more suspicious
 * @param triggered     true if the indicator fired
 * @param reason        human-readable reason for audit/UI
 */
public record FraudIndicatorResult(String indicatorName, int score, boolean triggered, String reason) {

    public static FraudIndicatorResult clean(String name) {
        return new FraudIndicatorResult(name, 0, false, "not triggered");
    }

    public static FraudIndicatorResult triggered(String name, int score, String reason) {
        return new FraudIndicatorResult(name, score, true, reason);
    }
}
