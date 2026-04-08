package com.claimsflow.claims.api.dto;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ClaimResponse(
        Long id,
        String claimRef,
        String policyNumber,
        String claimantName,
        BigDecimal amountClaimed,
        BigDecimal amountApproved,
        ClaimStatus status,
        Integer fraudScore,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(
                c.getId(),
                c.getClaimRef(),
                c.getPolicyNumber(),
                c.getClaimantName(),
                c.getAmountClaimed(),
                c.getAmountApproved(),
                c.getStatus(),
                c.getFraudScore(),
                c.getDescription(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
