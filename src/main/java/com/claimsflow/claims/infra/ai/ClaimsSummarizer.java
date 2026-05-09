package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;

/**
 * Port for AI-based claim summarization.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code !test} → {@link BedrockClaimsSummarizer} (real AWS Bedrock Claude)</li>
 *   <li>{@code test}  → {@link StubClaimsSummarizer} (deterministic, no AWS)</li>
 * </ul>
 */
public interface ClaimsSummarizer {
    String summarize(Claim claim);
}
