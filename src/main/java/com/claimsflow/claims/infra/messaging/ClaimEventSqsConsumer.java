package com.claimsflow.claims.infra.messaging;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.claims.infra.search.ClaimProjectionService;
import com.claimsflow.dashboard.DashboardBroadcaster;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQS long-poll consumer that closes the CQRS loop: outbox relay publishes
 * claim events to SQS, this consumer receives them and refreshes the
 * OpenSearch projection automatically.
 *
 * <p><b>Threading:</b> runs on a dedicated single thread via {@link SmartLifecycle},
 * NOT on the shared {@code @Scheduled} TaskScheduler. A 10-second long poll
 * inside a scheduled method would block the default single-thread scheduler
 * and starve the outbox relay.
 *
 * <p><b>Idempotency:</b> at-least-once delivery means duplicates are possible
 * (relay retry, visibility-timeout redelivery). Safe because the projection is
 * an idempotent upsert keyed by claimRef — re-projecting is a no-op-equivalent.
 *
 * <p><b>Poison messages:</b> unparseable payloads and events referencing
 * missing claims are deleted (logged at ERROR — production would route to a
 * DLQ). Transient failures (e.g. OpenSearch down) leave the message on the
 * queue for redelivery after the visibility timeout; on a FIFO queue this
 * intentionally blocks that claim's message group, preserving event order.
 */
@Slf4j
@Component
@Profile("!test")
public class ClaimEventSqsConsumer implements SmartLifecycle {

    private final SqsClient sqsClient;
    private final ClaimRepository claimRepository;
    private final ClaimProjectionService projectionService;
    private final DashboardBroadcaster dashboardBroadcaster;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final int waitTimeSeconds;
    private final int maxMessages;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claim-event-sqs-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    public ClaimEventSqsConsumer(SqsClient sqsClient,
                                 ClaimRepository claimRepository,
                                 ClaimProjectionService projectionService,
                                 DashboardBroadcaster dashboardBroadcaster,
                                 ObjectMapper objectMapper,
                                 @Value("${claimsflow.aws.sqs.claims-events-queue-url}") String queueUrl,
                                 @Value("${claimsflow.aws.sqs.consumer.wait-time-seconds:10}") int waitTimeSeconds,
                                 @Value("${claimsflow.aws.sqs.consumer.max-messages:10}") int maxMessages) {
        this.sqsClient = sqsClient;
        this.claimRepository = claimRepository;
        this.projectionService = projectionService;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
        this.waitTimeSeconds = waitTimeSeconds;
        this.maxMessages = maxMessages;
    }

    // ─── SmartLifecycle ──────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
        log.info("ClaimEventSqsConsumer started; long-polling {} (wait={}s, batch={})",
                queueUrl, waitTimeSeconds, maxMessages);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SQS consumer thread did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("ClaimEventSqsConsumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (Exception ex) {
                log.error("SQS poll cycle failed: {}; backing off 5s", ex.getMessage());
                sleepQuietly(5000);
            }
        }
    }

    /**
     * One receive → process → delete cycle. Package-private for unit testing
     * without the lifecycle thread.
     */
    void pollOnce() {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(waitTimeSeconds)
                        .maxNumberOfMessages(maxMessages)
                        .build())
                .messages();

        for (Message message : messages) {
            handle(message);
        }
    }

    private void handle(Message message) {
        Long claimId = null;
        try {
            JsonNode payload = objectMapper.readTree(message.body());
            claimId = payload.path("claimId").isNumber() ? payload.path("claimId").asLong() : null;
            if (claimId == null) {
                throw new IllegalArgumentException("payload has no numeric claimId field");
            }

            Claim claim = claimRepository.findById(claimId).orElse(null);
            if (claim == null) {
                // Outbox rows commit after the claim insert, so a missing claim
                // is permanent corruption, not a race — treat as poison.
                log.error("SQS event references missing claim id={}; deleting as poison. body={}",
                        claimId, message.body());
                delete(message);
                return;
            }

            projectionService.project(claim.getClaimRef());
            dashboardBroadcaster.broadcast();
            delete(message);
            log.debug("SQS event processed: claimRef={} type={}",
                    claim.getClaimRef(), payload.path("eventType").asText());

        } catch (JsonProcessingException | IllegalArgumentException poison) {
            log.error("Poison SQS message (unparseable), deleting. body={} error={}",
                    message.body(), poison.getMessage());
            delete(message);
        } catch (RuntimeException transientFailure) {
            // e.g. OpenSearch unavailable — leave on queue for redelivery after
            // the visibility timeout. FIFO ordering for this claim is preserved.
            log.warn("Transient failure processing SQS event claimId={}; will be redelivered: {}",
                    claimId, transientFailure.getMessage());
        }
    }

    private void delete(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
