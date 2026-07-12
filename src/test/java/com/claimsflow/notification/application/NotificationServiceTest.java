package com.claimsflow.notification.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.notification.domain.Notification;
import com.claimsflow.notification.domain.NotificationChannel;
import com.claimsflow.notification.domain.NotificationRepository;
import com.claimsflow.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;

    @InjectMocks NotificationService service;

    @Test
    void statusChangeFansOutOneNotificationPerChannel() {
        Claim claim = Claim.submit("CLM-1", "POL-1", "Jane Doe", new BigDecimal("500"), null);
        when(notificationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<Notification> created = service.claimStatusChanged(
                claim, ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW);

        assertThat(created).hasSize(3);
        assertThat(created).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(
                        NotificationChannel.EMAIL, NotificationChannel.SMS, NotificationChannel.IN_APP);
        assertThat(created).allSatisfy(n -> {
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getClaimRef()).isEqualTo("CLM-1");
            assertThat(n.getSubject()).contains("CLM-1").contains("under review");
            assertThat(n.getBody()).contains("Jane Doe").contains("POL-1");
        });
    }

    @Test
    void approvedNotificationIncludesApprovedAmount() {
        Claim claim = Claim.submit("CLM-2", "POL-1", "Bob", new BigDecimal("2000"), null);
        claim.transitionTo(ClaimStatus.UNDER_REVIEW);
        claim.transitionTo(ClaimStatus.ADJUDICATION);
        claim.transitionTo(ClaimStatus.APPROVED);
        claim.approve(new BigDecimal("1800"));
        when(notificationRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<Notification> created = service.claimStatusChanged(
                claim, ClaimStatus.ADJUDICATION, ClaimStatus.APPROVED);

        assertThat(created).allSatisfy(n -> {
            assertThat(n.getSubject()).contains("approved");
            assertThat(n.getBody()).contains("1800");
        });
    }
}
