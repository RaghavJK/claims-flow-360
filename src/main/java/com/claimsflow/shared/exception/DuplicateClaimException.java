package com.claimsflow.shared.exception;

public class DuplicateClaimException extends DomainException {
    public DuplicateClaimException(String message) {
        super(message);
    }
}
