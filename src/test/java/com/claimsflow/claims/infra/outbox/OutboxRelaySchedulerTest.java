package com.claimsflow.claims.infra.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock OutboxEventRepository outboxEventRepository;
    @Mock SqsClient sqsClient;

    private static final String QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/123/test.fifo";

    OutboxRelayScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxRelayScheduler(outboxEventRepository, sqsClient, QUEUE_URL);
    }

    @Test
    void relaySkipsWhenNoPendingEvents() {
        when(outboxEventRepository.findPendingBatch()).thenReturn(List.of());
        scheduler.relay();
        verifyNoInteractions(sqsClient);
    }

    @Test
    void relaysSendsPendingEventsToSqs() {
        OutboxEvent e1 = buildPendingEvent(1L, "CLM-A", "{\"eventType\":\"CLAIM_SUBMITTED\"}");
        OutboxEvent e2 = buildPendingEvent(2L, "CLM-B", "{\"eventType\":\"CLAIM_TRANSITIONED\"}");
        when(outboxEventRepository.findPendingBatch()).thenReturn(List.of(e1, e2));
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());

        scheduler.relay();

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient, times(2)).sendMessage(captor.capture());

        List<SendMessageRequest> requests = captor.getAllValues();
        assertThat(requests).extracting(SendMessageRequest::queueUrl).containsOnly(QUEUE_URL);
        assertThat(requests.get(0).messageGroupId()).isEqualTo("CLM-A");
        assertThat(requests.get(1).messageGroupId()).isEqualTo("CLM-B");
        assertThat(e1.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(e2.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void failedSqsSendMarksEventFailed() {
        OutboxEvent event = buildPendingEvent(3L, "CLM-C", "{}");
        when(outboxEventRepository.findPendingBatch()).thenReturn(List.of(event));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        scheduler.relay();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    void retryFailedResetsEligibleEventsBackToPending() {
        OutboxEvent failed = buildPendingEvent(4L, "CLM-D", "{}");
        failed.markFailed(); // retryCount=1
        when(outboxEventRepository.findRetryableFailed(3)).thenReturn(List.of(failed));

        scheduler.retryFailed();

        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    private OutboxEvent buildPendingEvent(long id, String claimRef, String payload) {
        return OutboxEvent.pending("Claim", claimRef, "CLAIM_SUBMITTED", payload);
    }
}
