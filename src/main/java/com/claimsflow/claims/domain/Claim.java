package com.claimsflow.claims.domain;

import com.claimsflow.shared.exception.InvalidClaimTransitionException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Claim aggregate root. Owns the workflow state machine and invariants.
 *
 * <p>The state-pattern-with-Memento described in the spec is simplified here
 * to an enum-driven transition map (see {@link ClaimStatus}) plus an append-only
 * {@link ClaimEvent} log for audit/replay. This gives us the same auditability
 * and replay characteristics with far less ceremony for a Week 1 vertical slice.
 */
@Entity
@Table(name = "claims")
@Getter
@NoArgsConstructor
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_ref", nullable = false, unique = true, length = 32)
    private String claimRef;

    @Column(name = "policy_number", nullable = false, length = 64)
    private String policyNumber;

    @Column(name = "claimant_name", nullable = false, length = 200)
    private String claimantName;

    @Column(name = "amount_claimed", nullable = false, precision = 15, scale = 2)
    private BigDecimal amountClaimed;

    @Column(name = "amount_approved", precision = 15, scale = 2)
    private BigDecimal amountApproved;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClaimStatus status;

    @Column(length = 2000)
    private String description;

    @Column(name = "fraud_score")
    private Integer fraudScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    private Claim(String claimRef,
                  String policyNumber,
                  String claimantName,
                  BigDecimal amountClaimed,
                  String description) {
        this.claimRef = claimRef;
        this.policyNumber = policyNumber;
        this.claimantName = claimantName;
        this.amountClaimed = amountClaimed;
        this.description = description;
        this.status = ClaimStatus.SUBMITTED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Claim submit(String claimRef,
                               String policyNumber,
                               String claimantName,
                               BigDecimal amountClaimed,
                               String description) {
        if (claimRef == null || claimRef.isBlank()) {
            throw new IllegalArgumentException("claimRef is required");
        }
        if (policyNumber == null || policyNumber.isBlank()) {
            throw new IllegalArgumentException("policyNumber is required");
        }
        if (claimantName == null || claimantName.isBlank()) {
            throw new IllegalArgumentException("claimantName is required");
        }
        if (amountClaimed == null || amountClaimed.signum() <= 0) {
            throw new IllegalArgumentException("amountClaimed must be > 0");
        }
        return new Claim(claimRef, policyNumber, claimantName, amountClaimed, description);
    }

    /**
     * Transitions the claim to the target status. Validates the transition
     * against {@link ClaimStatus#canTransitionTo(ClaimStatus)} and throws
     * {@link InvalidClaimTransitionException} on illegal transitions.
     */
    public ClaimStatus transitionTo(ClaimStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("target status is required");
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidClaimTransitionException(this.claimRef, this.status, target);
        }
        ClaimStatus previous = this.status;
        this.status = target;
        this.updatedAt = Instant.now();
        return previous;
    }

    public void assignFraudScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("fraud score must be between 0 and 100");
        }
        this.fraudScore = score;
        this.updatedAt = Instant.now();
    }

    public void approve(BigDecimal amountApproved) {
        if (this.status != ClaimStatus.APPROVED) {
            throw new IllegalStateException("Claim must be in APPROVED state to set approved amount");
        }
        if (amountApproved == null || amountApproved.signum() < 0) {
            throw new IllegalArgumentException("amountApproved must be >= 0");
        }
        this.amountApproved = amountApproved;
        this.updatedAt = Instant.now();
    }
}
