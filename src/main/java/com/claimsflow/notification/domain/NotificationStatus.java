package com.claimsflow.notification.domain;

/**
 * Delivery lifecycle. {@code DEAD} is the in-table dead-letter: delivery
 * failed the maximum retries and needs human attention — the row is never
 * picked up by the dispatcher again.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,   // transient failure; dispatcher retries until max attempts
    DEAD      // retries exhausted — dead-lettered for manual review
}
