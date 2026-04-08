package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chain of Responsibility across all registered {@link FraudIndicator}s.
 *
 * <p>Each indicator contributes independently; the chain aggregates the scores
 * and caps the total at 100. Spring auto-wires every {@link FraudIndicator} bean
 * in classpath order, so adding a new indicator is a zero-touch operation here.
 */
@Slf4j
@Component
public class FraudScoringChain {

    private final List<FraudIndicator> indicators;

    public FraudScoringChain(List<FraudIndicator> indicators) {
        this.indicators = List.copyOf(indicators);
        log.info("FraudScoringChain initialized with {} indicators: {}",
                indicators.size(),
                indicators.stream().map(FraudIndicator::name).toList());
    }

    public FraudScoreResult score(Claim claim) {
        List<FraudIndicatorResult> results = new ArrayList<>(indicators.size());
        int total = 0;
        for (FraudIndicator indicator : indicators) {
            FraudIndicatorResult result;
            try {
                result = indicator.evaluate(claim);
            } catch (RuntimeException ex) {
                log.warn("Indicator {} failed for claim {}; treating as clean", indicator.name(), claim.getClaimRef(), ex);
                result = FraudIndicatorResult.clean(indicator.name());
            }
            results.add(result);
            total += result.score();
        }
        int capped = Math.min(100, Math.max(0, total));
        return new FraudScoreResult(capped, results);
    }
}
