package com.claimsflow.notification.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.notification.domain.Notification;
import com.claimsflow.notification.domain.NotificationChannel;
import com.claimsflow.notification.domain.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Creates notification rows when claim state changes (FR-08).
 *
 * <p>Called inside the {@code WorkflowService} transaction — the rows commit
 * atomically with the transition (outbox principle). Actual delivery is
 * async via {@link NotificationDispatcher}, so an SNS outage never blocks
 * or rolls back a claim transition.
 *
 * <p>Recipient is the claimant name for now — a contact-info lookup against
 * the future {@code customers} table replaces this when that context lands.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Fans out one notification per channel for a claim status change.
     * Must be invoked within the caller's transaction.
     */
    public List<Notification> claimStatusChanged(Claim claim, ClaimStatus from, ClaimStatus to) {
        String subject = "Claim %s: %s".formatted(claim.getClaimRef(), statusHeadline(to));
        String body = """
                Dear %s,

                Your claim %s (policy %s) has moved from %s to %s.
                Amount claimed: %s%s

                — ClaimsFlow360""".formatted(
                claim.getClaimantName(),
                claim.getClaimRef(),
                claim.getPolicyNumber(),
                from, to,
                claim.getAmountClaimed(),
                claim.getAmountApproved() != null
                        ? "\nAmount approved: " + claim.getAmountApproved()
                        : "");

        List<Notification> created = List.of(
                Notification.pending(claim.getClaimRef(), NotificationChannel.EMAIL, claim.getClaimantName(), subject, body),
                Notification.pending(claim.getClaimRef(), NotificationChannel.SMS, claim.getClaimantName(), subject, body),
                Notification.pending(claim.getClaimRef(), NotificationChannel.IN_APP, claim.getClaimantName(), subject, body));

        List<Notification> saved = notificationRepository.saveAll(created);
        log.info("Queued {} notifications for claim {} transition {} -> {}",
                saved.size(), claim.getClaimRef(), from, to);
        return saved;
    }

    private String statusHeadline(ClaimStatus status) {
        return switch (status) {
            case SUBMITTED -> "received";
            case UNDER_REVIEW -> "under review";
            case ADJUDICATION -> "in adjudication";
            case APPROVED -> "approved";
            case DENIED -> "denied";
        };
    }
}
