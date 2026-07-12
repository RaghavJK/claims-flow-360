package com.claimsflow.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single outbound notification (FR-08).
 *
 * <p>Same shape as the transactional outbox: the row is written inside the
 * business transaction that caused it (claim status change), and a separate
 * dispatcher delivers asynchronously — a channel outage never rolls back or
 * delays a claim transition.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_ref", nullable = false, length = 32)
    private String claimRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(nullable = false, length = 200)
    private String recipient;

    @Column(nullable = false, length = 255)
    private String subject;

    @Lob
    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    private Notification(String claimRef, NotificationChannel channel,
                         String recipient, String subject, String body) {
        this.claimRef = claimRef;
        this.channel = channel;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.status = NotificationStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = Instant.now();
    }

    public static Notification pending(String claimRef, NotificationChannel channel,
                                       String recipient, String subject, String body) {
        return new Notification(claimRef, channel, recipient, subject, body);
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
    }

    /** Increments the attempt count; dead-letters once maxRetries is reached. */
    public void markFailed(int maxRetries) {
        this.retryCount++;
        this.status = retryCount >= maxRetries ? NotificationStatus.DEAD : NotificationStatus.FAILED;
    }
}
