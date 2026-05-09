package com.claimsflow.claims.infra.outbox;

import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.ClaimStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxClaimEventPublisherTest {

    @Mock OutboxEventRepository outboxEventRepository;
    @InjectMocks OutboxClaimEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxClaimEventPublisher(outboxEventRepository, new ObjectMapper());
    }

    @Test
    void publishSavesOutboxRowWithCorrectFields() {
        ClaimEvent event = ClaimEvent.transitioned(42L, ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW, "adj-1", "review started");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        publisher.publish(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getAggregateType()).isEqualTo("Claim");
        assertThat(saved.getAggregateId()).isEqualTo("42");
        assertThat(saved.getEventType()).isEqualTo("CLAIM_TRANSITIONED");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getPayload()).contains("CLAIM_TRANSITIONED");
        assertThat(saved.getPayload()).contains("UNDER_REVIEW");
    }

    @Test
    void publishedEventPayloadIsValidJson() throws Exception {
        ClaimEvent event = ClaimEvent.submitted(99L, "portal");
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        publisher.publish(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        String payload = captor.getValue().getPayload();

        // Must be valid JSON
        new ObjectMapper().readTree(payload);
        assertThat(payload).contains("CLAIM_SUBMITTED");
    }
}
