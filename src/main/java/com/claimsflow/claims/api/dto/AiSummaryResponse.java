package com.claimsflow.claims.api.dto;

import com.claimsflow.claims.domain.ai.AiSummary;

import java.time.Instant;

public record AiSummaryResponse(
        Long id,
        String claimRef,
        String modelId,
        String summary,
        Instant generatedAt
) {
    public static AiSummaryResponse from(AiSummary s) {
        return new AiSummaryResponse(s.getId(), s.getClaimRef(), s.getModelId(), s.getSummary(), s.getGeneratedAt());
    }
}
