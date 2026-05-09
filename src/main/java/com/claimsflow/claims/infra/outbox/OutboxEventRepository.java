package com.claimsflow.claims.infra.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Fetch the next batch of pending outbox events ordered by creation time.
     * Limit enforced in JPQL (portable; adjust size in relay scheduler config).
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC LIMIT 50")
    List<OutboxEvent> findPendingBatch();

    /**
     * Retry failed events where retryCount has not yet exceeded the max.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'FAILED' AND o.retryCount < :maxRetries ORDER BY o.createdAt ASC LIMIT 20")
    List<OutboxEvent> findRetryableFailed(int maxRetries);
}
