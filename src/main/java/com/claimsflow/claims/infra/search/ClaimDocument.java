package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * OpenSearch document for the CQRS read model.
 *
 * <p>Denormalized view of a claim — optimized for search, aggregations, and
 * dashboard queries. The write model (MySQL claims table) is normalized; this
 * document trades storage for query performance.
 *
 * <p>Index: {@code claims} (one document per claim, keyed by claimRef).
 */
@Getter
@Builder
public class ClaimDocument {

    public static final String INDEX = "claims";

    private String claimRef;
    private String policyNumber;
    private String claimantName;
    private ClaimStatus status;
    private BigDecimal amountClaimed;
    private BigDecimal amountApproved;
    private Integer fraudScore;
    private boolean fraudFlagged;          // fraudScore >= threshold
    private String description;
    private String aiSummary;              // from ai_summaries; null until generated
    private Instant createdAt;
    private Instant updatedAt;

    /** Build from the MySQL aggregate root for initial projection. */
    public static ClaimDocument from(Claim claim, int fraudThreshold) {
        return ClaimDocument.builder()
                .claimRef(claim.getClaimRef())
                .policyNumber(claim.getPolicyNumber())
                .claimantName(claim.getClaimantName())
                .status(claim.getStatus())
                .amountClaimed(claim.getAmountClaimed())
                .amountApproved(claim.getAmountApproved())
                .fraudScore(claim.getFraudScore())
                .fraudFlagged(claim.getFraudScore() != null && claim.getFraudScore() >= fraudThreshold)
                .description(claim.getDescription())
                .createdAt(claim.getCreatedAt())
                .updatedAt(claim.getUpdatedAt())
                .build();
    }
}
