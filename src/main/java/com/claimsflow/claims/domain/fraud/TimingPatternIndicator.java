package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * Fires when a claim is submitted during unusual hours (nighttime window).
 * Unusual timing is one of the weaker signals in the chain (low weight).
 */
@Component
public class TimingPatternIndicator implements FraudIndicator {

    private static final String NAME = "TIMING_PATTERN";
    private static final LocalTime NIGHT_START = LocalTime.of(0, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(5, 0);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FraudIndicatorResult evaluate(Claim claim) {
        if (claim.getCreatedAt() == null) {
            return FraudIndicatorResult.clean(NAME);
        }
        LocalTime t = claim.getCreatedAt().atZone(ZoneOffset.UTC).toLocalTime();
        if (!t.isBefore(NIGHT_START) && t.isBefore(NIGHT_END)) {
            return FraudIndicatorResult.triggered(
                    NAME,
                    15,
                    "submitted at %s UTC (nighttime window)".formatted(t));
        }
        return FraudIndicatorResult.clean(NAME);
    }
}
