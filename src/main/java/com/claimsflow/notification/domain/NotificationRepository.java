package com.claimsflow.notification.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Dispatcher batch: new + retryable rows, oldest first. DEAD is never picked up. */
    List<Notification> findByStatusInOrderByCreatedAtAsc(List<NotificationStatus> statuses, Limit limit);

    List<Notification> findByClaimRefOrderByCreatedAtDesc(String claimRef);
}
