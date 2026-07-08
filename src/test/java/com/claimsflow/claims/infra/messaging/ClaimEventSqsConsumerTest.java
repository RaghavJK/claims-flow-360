package com.claimsflow.claims.infra.messaging;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.claims.infra.search.ClaimProjectionService;
import com.claimsflow.dashboard.DashboardBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimEventSqsConsumerTest {

    private static final String QUEUE_URL = "https://sqs.test/queue.fifo";

    @Mock SqsClient sqsClient;
    @Mock ClaimRepository claimRepository;
    @Mock ClaimProjectionService projectionService;
    @Mock DashboardBroadcaster dashboardBroadcaster;

    ClaimEventSqsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ClaimEventSqsConsumer(
                sqsClient, claimRepository, projectionService, dashboardBroadcaster,
                new ObjectMapper(), QUEUE_URL, 10, 10);
    }

    @Test
    void validEventProjectsBroadcastsAndDeletes() {
        Claim claim = Claim.submit("CLM-42", "POL-1", "Jane", new BigDecimal("500"), null);
        stubReceive(message("{\"eventType\":\"CLAIM_SUBMITTED\",\"claimId\":42}", "rh-1"));
        when(claimRepository.findById(42L)).thenReturn(Optional.of(claim));

        consumer.pollOnce();

        verify(projectionService).project("CLM-42");
        verify(dashboardBroadcaster).broadcast();
        verify(sqsClient).deleteMessage(argThat((DeleteMessageRequest r) ->
                r.receiptHandle().equals("rh-1") && r.queueUrl().equals(QUEUE_URL)));
    }

    @Test
    void unparseablePayloadIsDeletedAsPoisonWithoutProjecting() {
        stubReceive(message("this is not json", "rh-poison"));

        consumer.pollOnce();

        verifyNoInteractions(projectionService, dashboardBroadcaster);
        verify(sqsClient).deleteMessage(argThat((DeleteMessageRequest r) ->
                r.receiptHandle().equals("rh-poison")));
    }

    @Test
    void payloadWithoutClaimIdIsDeletedAsPoison() {
        stubReceive(message("{\"eventType\":\"CLAIM_SUBMITTED\"}", "rh-nofield"));

        consumer.pollOnce();

        verifyNoInteractions(projectionService);
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void eventForMissingClaimIsDeletedAsPoison() {
        stubReceive(message("{\"claimId\":999}", "rh-missing"));
        when(claimRepository.findById(999L)).thenReturn(Optional.empty());

        consumer.pollOnce();

        verifyNoInteractions(projectionService);
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void transientProjectionFailureLeavesMessageForRedelivery() {
        Claim claim = Claim.submit("CLM-7", "POL-1", "Bob", new BigDecimal("100"), null);
        stubReceive(message("{\"claimId\":7}", "rh-transient"));
        when(claimRepository.findById(7L)).thenReturn(Optional.of(claim));
        doThrow(new RuntimeException("OpenSearch unavailable"))
                .when(projectionService).project("CLM-7");

        consumer.pollOnce();

        // Not deleted — SQS redelivers after the visibility timeout
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        verifyNoInteractions(dashboardBroadcaster);
    }

    @Test
    void emptyReceiveDoesNothing() {
        stubReceive();

        consumer.pollOnce();

        verifyNoInteractions(claimRepository, projectionService, dashboardBroadcaster);
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void stubReceive(Message... messages) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private Message message(String body, String receiptHandle) {
        return Message.builder().body(body).receiptHandle(receiptHandle).build();
    }
}
