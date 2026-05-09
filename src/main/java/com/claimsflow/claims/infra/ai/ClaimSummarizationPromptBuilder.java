package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;
import org.springframework.stereotype.Component;

/**
 * Assembles the Bedrock Claude prompt for claims summarization.
 *
 * <p>Kept in its own class so it can be unit-tested in isolation and
 * iterated on independently of the Bedrock client plumbing.
 *
 * <p>Token budget: ~300 prompt tokens + 500 completion tokens = 800 total.
 * Well within the Claude Haiku context window and free-tier limits.
 */
@Component
public class ClaimSummarizationPromptBuilder {

    private static final String TEMPLATE = """
            You are a senior insurance claims analyst at a major BFSI firm.
            Analyze the following insurance claim and produce a concise structured summary.

            --- CLAIM DETAILS ---
            Claim Reference : %s
            Policy Number   : %s
            Claimant Name   : %s
            Amount Claimed  : $%s
            Current Status  : %s
            Fraud Score     : %d / 100  (threshold for SIU referral: %d)
            Description     : %s

            --- INSTRUCTIONS ---
            Respond ONLY in the following JSON format (no markdown fences):
            {
              "keyFacts": "<2-3 sentence summary of the claim>",
              "coverageMatch": "<brief assessment of likely policy coverage fit>",
              "redFlags": "<list any red flags, or 'None detected' if clean>",
              "recommendedAction": "<one of: AUTO_APPROVE | MANUAL_REVIEW | SIU_REFERRAL | DENY>"
            }
            """;

    public String build(Claim claim, int fraudThreshold) {
        return TEMPLATE.formatted(
                claim.getClaimRef(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getAmountClaimed().toPlainString(),
                claim.getStatus().name(),
                claim.getFraudScore() != null ? claim.getFraudScore() : 0,
                fraudThreshold,
                claim.getDescription() != null ? claim.getDescription() : "Not provided"
        );
    }
}
