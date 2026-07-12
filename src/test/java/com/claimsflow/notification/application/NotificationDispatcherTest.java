package com.claimsflow.notification.application;

import com.claimsflow.notification.domain.Notification;
import com.claimsflow.notification.domain.NotificationChannel;
import com.claimsflow.notification.domain.NotificationRepository;
import com.claimsflow.notification.domain.NotificationStatus;
import com.claimsflow.notification.infra.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    private static final int MAX_RETRIES = 3;

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationSender sender;

    NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(notificationRepository, sender, MAX_RETRIES, 50);
    }

    @Test
    void sendsPendingNotificationsAndMarksSent() {
        Notification notification = pending();
        stubBatch(List.of(notification));

        dispatcher.dispatch();

        verify(sender).send(notification);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isNotNull();
    }

    @Test
    void deliveryFailureIncrementsRetryAndStaysRetryable() {
        Notification notification = pending();
        stubBatch(List.of(notification));
        doThrow(new RuntimeException("SNS unavailable")).when(sender).send(notification);

        dispatcher.dispatch();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesDeadLetterTheNotification() {
        Notification notification = pending();
        stubBatch(List.of(notification));
        doThrow(new RuntimeException("SNS unavailable")).when(sender).send(notification);

        dispatcher.dispatch();   // attempt 1 → FAILED
        dispatcher.dispatch();   // attempt 2 → FAILED
        dispatcher.dispatch();   // attempt 3 → DEAD

        assertThat(notification.getRetryCount()).isEqualTo(MAX_RETRIES);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD);
    }

    @Test
    void oneFailureDoesNotBlockRestOfBatch() {
        Notification bad = pending();
        Notification good = pending();
        stubBatch(List.of(bad, good));
        doAnswer(inv -> {
            if (inv.getArgument(0) == bad) throw new RuntimeException("boom");
            return null;
        }).when(sender).send(any(Notification.class));

        dispatcher.dispatch();

        assertThat(bad.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(good.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void emptyBatchIsNoop() {
        stubBatch(List.of());
        dispatcher.dispatch();
        verifyNoInteractions(sender);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void stubBatch(List<Notification> batch) {
        when(notificationRepository.findByStatusInOrderByCreatedAtAsc(anyList(), any(Limit.class)))
                .thenReturn(batch);
    }

    private Notification pending() {
        return Notification.pending("CLM-1", NotificationChannel.EMAIL, "Jane", "subject", "body");
    }
}
