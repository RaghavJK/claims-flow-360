package com.claimsflow.claims.infra.outbox;

import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.infra.messaging.ClaimEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Production publisher: writes a JSON-serialised event to the outbox_events
 * table within the caller's @Transactional, guaranteeing atomicity with the
 * business write.  {@link OutboxRelayScheduler} reads pending rows and pushes
 * them to SQS asynchronously.
 */
@Slf4j
@Component
@Profile("!test")
public class OutboxClaimEventPublisher implements ClaimEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxClaimEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ClaimEvent event) {
        String payload = serialise(event);
        OutboxEvent outbox = OutboxEvent.pending(
                "Claim",
                String.valueOf(event.getClaimId()),
                event.getEventType(),
                payload);
        outboxEventRepository.save(outbox);
        log.debug("Outbox row created for claim={} type={} outboxId={}",
                event.getClaimId(), event.getEventType(), outbox.getId());
    }

    private String serialise(ClaimEvent event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "eventType",   event.getEventType(),
                    "claimId",     event.getClaimId(),
                    "fromStatus",  event.getFromStatus() != null ? event.getFromStatus().name() : "",
                    "toStatus",    event.getToStatus()   != null ? event.getToStatus().name()   : "",
                    "actorId",     event.getActorId() != null ? event.getActorId() : "",
                    "occurredAt",  event.getOccurredAt().toString()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise ClaimEvent id=" + event.getId(), e);
        }
    }
}
