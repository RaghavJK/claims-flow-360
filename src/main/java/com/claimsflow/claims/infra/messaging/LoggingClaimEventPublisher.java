package com.claimsflow.claims.infra.messaging;

import com.claimsflow.claims.domain.ClaimEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-profile publisher: logs the event and does nothing else.
 * Used in unit tests and the Spring context-load test so no AWS services are needed.
 */
@Slf4j
@Component
@Profile("test")
public class LoggingClaimEventPublisher implements ClaimEventPublisher {

    @Override
    public void publish(ClaimEvent event) {
        log.debug("[test-stub] claim event type={} claimId={} from={} to={}",
                event.getEventType(), event.getClaimId(), event.getFromStatus(), event.getToStatus());
    }
}
