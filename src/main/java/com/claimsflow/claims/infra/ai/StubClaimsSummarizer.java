package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-profile summarizer: returns a deterministic stub JSON string.
 * No AWS credentials required.
 */
@Slf4j
@Component
@Profile("test")
public class StubClaimsSummarizer implements ClaimsSummarizer {

    @Override
    public String summarize(Claim claim) {
        log.debug("[test-stub] summarize called for claimRef={}", claim.getClaimRef());
        return """
                {"keyFacts":"Test summary for %s","coverageMatch":"Likely covered",
                 "redFlags":"None detected","recommendedAction":"AUTO_APPROVE"}
                """.formatted(claim.getClaimRef());
    }
}
