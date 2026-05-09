package com.claimsflow.claims.infra.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

/**
 * Polls the outbox_events table and forwards PENDING rows to SQS.
 *
 * <p><b>Pattern:</b> Transactional Outbox.  The relay runs in its own
 * transaction, separate from the business write.  This gives at-least-once
 * delivery semantics: if SQS is unavailable the row stays PENDING and the
 * next scheduler tick retries.  Idempotency on the consumer side handles
 * any duplicates.
 *
 * <p><b>Concurrency:</b> For a single-instance deployment this is safe as-is.
 * For multi-instance add a SELECT FOR UPDATE or use a distributed lock
 * (e.g. Redis + Redisson) to prevent duplicate relay.
 *
 * <p>Not active in the {@code test} profile — tests mock the SqsClient
 * and call the relay directly via {@link #relay()}.
 */
@Slf4j
@Component
@Profile("!test")
@EnableScheduling
public class OutboxRelayScheduler {

    private static final int MAX_RETRIES = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final SqsClient sqsClient;
    private final String queueUrl;

    public OutboxRelayScheduler(OutboxEventRepository outboxEventRepository,
                                SqsClient sqsClient,
                                @Value("${claimsflow.aws.sqs.claims-events-queue-url}") String queueUrl) {
        this.outboxEventRepository = outboxEventRepository;
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    @Scheduled(fixedDelayString = "${claimsflow.outbox.relay-interval-ms:2000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingBatch();
        if (pending.isEmpty()) return;

        log.debug("Outbox relay: processing {} pending event(s)", pending.size());
        for (OutboxEvent event : pending) {
            sendToSqs(event);
        }
    }

    @Scheduled(fixedDelayString = "${claimsflow.outbox.retry-interval-ms:30000}")
    @Transactional
    public void retryFailed() {
        List<OutboxEvent> retryable = outboxEventRepository.findRetryableFailed(MAX_RETRIES);
        if (retryable.isEmpty()) return;
        log.info("Outbox retry: re-queuing {} failed event(s)", retryable.size());
        retryable.forEach(OutboxEvent::resetToPending);
    }

    private void sendToSqs(OutboxEvent event) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(event.getPayload())
                    .messageGroupId(event.getAggregateId())   // FIFO queue ordering per claim
                    .messageDeduplicationId(String.valueOf(event.getId()))
                    .build());
            event.markSent();
            log.debug("Outbox event {} sent to SQS type={}", event.getId(), event.getEventType());
        } catch (Exception ex) {
            log.error("Failed to send outbox event {} to SQS: {}", event.getId(), ex.getMessage());
            event.markFailed();
        }
    }
}
