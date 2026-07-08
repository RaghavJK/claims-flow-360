package com.claimsflow.shared.exception;

public class CustomerNotFoundException extends DomainException {
    public CustomerNotFoundException(String policyNumber) {
        super("No claims found for policy: " + policyNumber);
    }
}
