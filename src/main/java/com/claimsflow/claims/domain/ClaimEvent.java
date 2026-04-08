package com.claimsflow.claims.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable audit record for every state-changing action on a {@link Claim}.
 * Append-only. Powers full replay for audit and reconstruction.
 */
@Entity
@Table(name = "claim_events")
@Getter
@NoArgsConstructor
public class ClaimEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private ClaimStatus toStatus;

    @Column(name = "actor_id", length = 128)
    private String actorId;

    @Lob
    @Column
    private String metadata;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    private ClaimEvent(Long claimId, String eventType, ClaimStatus from, ClaimStatus to, String actorId, String metadata) {
        this.claimId = claimId;
        this.eventType = eventType;
        this.fromStatus = from;
        this.toStatus = to;
        this.actorId = actorId;
        this.metadata = metadata;
        this.occurredAt = Instant.now();
    }

    public static ClaimEvent submitted(Long claimId, String actorId) {
        return new ClaimEvent(claimId, "CLAIM_SUBMITTED", null, ClaimStatus.SUBMITTED, actorId, null);
    }

    public static ClaimEvent transitioned(Long claimId, ClaimStatus from, ClaimStatus to, String actorId, String metadata) {
        return new ClaimEvent(claimId, "CLAIM_TRANSITIONED", from, to, actorId, metadata);
    }

    public static ClaimEvent fraudScored(Long claimId, String actorId, String metadata) {
        return new ClaimEvent(claimId, "CLAIM_FRAUD_SCORED", null, null, actorId, metadata);
    }
}
