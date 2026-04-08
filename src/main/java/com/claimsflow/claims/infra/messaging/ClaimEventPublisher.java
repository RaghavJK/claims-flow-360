package com.claimsflow.claims.infra.messaging;

import com.claimsflow.claims.domain.ClaimEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events for CQRS read-model projection and downstream consumers.
 *
 * <p>Week 1 is a stub that only logs. The real implementation (transactional
 * outbox → SQS → OpenSearch projection) is on the Week 2+ backlog.
 */
@Slf4j
@Component
public class ClaimEventPublisher {

    public void publish(ClaimEvent event) {
        log.info("[outbox-stub] publishing claim event type={} claimId={} from={} to={}",
                event.getEventType(), event.getClaimId(), event.getFromStatus(), event.getToStatus());
    }
}
