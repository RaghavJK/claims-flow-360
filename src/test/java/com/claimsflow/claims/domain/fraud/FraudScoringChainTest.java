package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringChainTest {

    private static Claim sampleClaim(BigDecimal amount) {
        return Claim.submit("CLM-TEST", "POL-1", "John", amount, null);
    }

    @Test
    void cleanIndicatorsProduceZeroScore() {
        List<FraudIndicator> indicators = List.of(
                stub("A", FraudIndicatorResult.clean("A")),
                stub("B", FraudIndicatorResult.clean("B")));
        FraudScoringChain chain = new FraudScoringChain(indicators);

        FraudScoreResult result = chain.score(sampleClaim(new BigDecimal("100")));
        assertThat(result.totalScore()).isZero();
        assertThat(result.indicatorResults()).hasSize(2).allMatch(r -> !r.triggered());
    }

    @Test
    void triggeredIndicatorsAreAggregated() {
        FraudIndicator i1 = stub("A", FraudIndicatorResult.triggered("A", 30, "boom"));
        FraudIndicator i2 = stub("B", FraudIndicatorResult.triggered("B", 40, "bang"));
        FraudScoringChain chain = new FraudScoringChain(List.of(i1, i2));

        FraudScoreResult result = chain.score(sampleClaim(new BigDecimal("100")));
        assertThat(result.totalScore()).isEqualTo(70);
        assertThat(result.exceeds(70)).isTrue();
        assertThat(result.exceeds(71)).isFalse();
    }

    @Test
    void totalScoreIsCappedAt100() {
        FraudIndicator i1 = stub("A", FraudIndicatorResult.triggered("A", 80, "x"));
        FraudIndicator i2 = stub("B", FraudIndicatorResult.triggered("B", 80, "y"));
        FraudScoringChain chain = new FraudScoringChain(List.of(i1, i2));

        FraudScoreResult result = chain.score(sampleClaim(new BigDecimal("100")));
        assertThat(result.totalScore()).isEqualTo(100);
    }

    @Test
    void failingIndicatorIsTreatedAsClean() {
        FraudIndicator boom = new FraudIndicator() {
            @Override public String name() { return "BOOM"; }
            @Override public FraudIndicatorResult evaluate(Claim claim) { throw new RuntimeException("kaboom"); }
        };
        FraudIndicator ok = stub("OK", FraudIndicatorResult.triggered("OK", 25, "ok"));
        FraudScoringChain chain = new FraudScoringChain(List.of(boom, ok));

        FraudScoreResult result = chain.score(sampleClaim(new BigDecimal("100")));
        assertThat(result.totalScore()).isEqualTo(25);
        assertThat(result.indicatorResults()).extracting(FraudIndicatorResult::indicatorName)
                .containsExactly("BOOM", "OK");
    }

    @Test
    void amountThresholdIndicatorFiresAboveThreshold() {
        AmountThresholdIndicator indicator = new AmountThresholdIndicator(new BigDecimal("50000"));
        FraudIndicatorResult low = indicator.evaluate(sampleClaim(new BigDecimal("10000")));
        FraudIndicatorResult high = indicator.evaluate(sampleClaim(new BigDecimal("75000")));
        assertThat(low.triggered()).isFalse();
        assertThat(high.triggered()).isTrue();
        assertThat(high.score()).isEqualTo(30);
    }

    private static FraudIndicator stub(String name, FraudIndicatorResult result) {
        return new FraudIndicator() {
            @Override public String name() { return name; }
            @Override public FraudIndicatorResult evaluate(Claim claim) { return result; }
        };
    }

}
