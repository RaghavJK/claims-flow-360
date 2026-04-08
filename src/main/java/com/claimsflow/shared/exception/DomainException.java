package com.claimsflow.shared.exception;

/** Base class for all domain-level exceptions in ClaimsFlow360. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }
}
