package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fires when a claim's amount exceeds a configured high-value threshold.
 * High-value claims are not inherently fraudulent but warrant extra scrutiny.
 */
@Component
public class AmountThresholdIndicator implements FraudIndicator {

    private static final String NAME = "AMOUNT_THRESHOLD";

    private final BigDecimal highThreshold;

    public AmountThresholdIndicator(@Value("${claimsflow.fraud.amount-high-threshold:50000}") BigDecimal highThreshold) {
        this.highThreshold = highThreshold;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FraudIndicatorResult evaluate(Claim claim) {
        if (claim.getAmountClaimed() != null && claim.getAmountClaimed().compareTo(highThreshold) > 0) {
            return FraudIndicatorResult.triggered(
                    NAME,
                    30,
                    "amount %s exceeds threshold %s".formatted(claim.getAmountClaimed(), highThreshold));
        }
        return FraudIndicatorResult.clean(NAME);
    }
}
