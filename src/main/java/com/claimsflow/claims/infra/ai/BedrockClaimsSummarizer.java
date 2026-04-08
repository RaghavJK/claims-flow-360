package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub for AWS Bedrock (Claude) claim summarization.
 *
 * <p>Week 1 returns a deterministic local summary so unit tests and local dev
 * require no AWS credentials. The real Bedrock Runtime client + prompt
 * assembly lands in Week 2 alongside the OpenSearch projection. Circuit
 * breaker and retry annotations are wired in now so the surrounding code
 * already observes the production resilience contract.
 */
@Slf4j
@Component
public class BedrockClaimsSummarizer {

    @CircuitBreaker(name = "bedrockSummarizer", fallbackMethod = "fallbackSummary")
    @Retry(name = "bedrockSummarizer")
    public String summarize(Claim claim) {
        log.debug("Generating (stub) summary for claim {}", claim.getClaimRef());
        return """
                Claim %s on policy %s.
                Claimant: %s. Amount claimed: %s. Current status: %s.
                [stub summary — Bedrock integration lands in Week 2]
                """.formatted(
                claim.getClaimRef(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getAmountClaimed(),
                claim.getStatus());
    }

    @SuppressWarnings("unused")
    private String fallbackSummary(Claim claim, Throwable ex) {
        log.warn("Bedrock summarizer fallback for claim {}: {}", claim.getClaimRef(), ex.toString());
        return "Summary unavailable (service degraded).";
    }
}
