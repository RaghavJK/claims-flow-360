package com.claimsflow.shared.exception;

import com.claimsflow.claims.domain.ClaimStatus;

public class InvalidClaimTransitionException extends DomainException {
    public InvalidClaimTransitionException(String claimRef, ClaimStatus from, ClaimStatus to) {
        super("Invalid transition for claim %s: %s -> %s".formatted(claimRef, from, to));
    }
}
