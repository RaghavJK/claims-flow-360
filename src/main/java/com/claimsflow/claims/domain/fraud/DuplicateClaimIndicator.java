package com.claimsflow.claims.domain.fraud;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Fires when another claim exists on the same policy within a short window
 * (potential duplicate submission or suspicious pattern).
 */
@Component
public class DuplicateClaimIndicator implements FraudIndicator {

    private static final String NAME = "DUPLICATE_CLAIM";
    private static final long WINDOW_HOURS = 24;

    private final ClaimRepository claimRepository;

    public DuplicateClaimIndicator(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FraudIndicatorResult evaluate(Claim claim) {
        Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);
        long count = claimRepository.countByPolicyNumberAndCreatedAtAfterAndIdNot(
                claim.getPolicyNumber(),
                since,
                claim.getId() == null ? -1L : claim.getId());
        if (count > 0) {
            return FraudIndicatorResult.triggered(
                    NAME,
                    40,
                    "%d other claim(s) on policy %s within %dh".formatted(count, claim.getPolicyNumber(), WINDOW_HOURS));
        }
        return FraudIndicatorResult.clean(NAME);
    }
}
