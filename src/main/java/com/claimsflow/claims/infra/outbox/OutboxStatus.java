package com.claimsflow.claims.infra.outbox;

public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
