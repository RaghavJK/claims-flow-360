package com.claimsflow.shared.exception;

public class ClaimNotFoundException extends DomainException {
    public ClaimNotFoundException(String claimRef) {
        super("Claim not found: " + claimRef);
    }
}
