package com.claimsflow.notification.application;

import com.claimsflow.notification.domain.Notification;
import com.claimsflow.notification.domain.NotificationRepository;
import com.claimsflow.notification.domain.NotificationStatus;
import com.claimsflow.notification.infra.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Delivers PENDING/FAILED notifications on a schedule (5 s default).
 *
 * <p>Retry policy: a delivery failure increments {@code retryCount}; at
 * {@code maxRetries} (3) the row is dead-lettered (status DEAD) and never
 * retried again — the in-table equivalent of an SQS DLQ, queryable for a
 * support dashboard.
 *
 * <p>Runs only when scheduling is enabled (non-test profiles — see
 * {@code SchedulingConfig}); tests invoke {@link #dispatch()} directly.
 */
@Slf4j
@Component
public class NotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationSender sender;
    private final int maxRetries;
    private final int batchSize;

    public NotificationDispatcher(NotificationRepository notificationRepository,
                                  NotificationSender sender,
                                  @Value("${claimsflow.notifications.max-retries:3}") int maxRetries,
                                  @Value("${claimsflow.notifications.batch-size:50}") int batchSize) {
        this.notificationRepository = notificationRepository;
        this.sender = sender;
        this.maxRetries = maxRetries;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${claimsflow.notifications.dispatch-interval-ms:5000}")
    @Transactional
    public void dispatch() {
        List<Notification> batch = notificationRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(NotificationStatus.PENDING, NotificationStatus.FAILED),
                Limit.of(batchSize));
        if (batch.isEmpty()) return;

        int sent = 0;
        int failed = 0;
        for (Notification notification : batch) {
            try {
                sender.send(notification);
                notification.markSent();
                sent++;
            } catch (RuntimeException ex) {
                notification.markFailed(maxRetries);
                failed++;
                if (notification.getStatus() == NotificationStatus.DEAD) {
                    log.error("Notification {} dead-lettered after {} attempts: {}",
                            notification.getId(), notification.getRetryCount(), ex.getMessage());
                } else {
                    log.warn("Notification {} delivery failed (attempt {}): {}",
                            notification.getId(), notification.getRetryCount(), ex.getMessage());
                }
            }
        }
        log.info("Notification dispatch: {} sent, {} failed", sent, failed);
    }
}
