package com.claimsflow.claims.infra.messaging;

import com.claimsflow.claims.domain.ClaimEvent;

/**
 * Port for publishing domain events out of the claims bounded context.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@code test} profile → {@link LoggingClaimEventPublisher} (logs, no I/O)</li>
 *   <li>{@code !test} profile → {@link OutboxClaimEventPublisher} (writes to outbox_events table,
 *       relayed asynchronously to SQS by {@link com.claimsflow.claims.infra.outbox.OutboxRelayScheduler})</li>
 * </ul>
 */
public interface ClaimEventPublisher {

    /**
     * Publishes a domain event. Must be called within an active transaction so
     * the outbox write is atomic with the business data write.
     */
    void publish(ClaimEvent event);
}
