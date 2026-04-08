package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;

/**
 * A single fraud signal. Implementations must be pure and stateless — all
 * inputs come through the {@link Claim} or are injected at construction time.
 *
 * <p>Scores returned are 0..100 weighted contributions that the
 * {@link FraudScoringChain} aggregates.
 */
public interface FraudIndicator {

    /**
     * @return a stable name used in audit logs and the indicator breakdown JSON
     */
    String name();

    /**
     * Evaluate the claim and return the contribution of this indicator.
     */
    FraudIndicatorResult evaluate(Claim claim);
}
