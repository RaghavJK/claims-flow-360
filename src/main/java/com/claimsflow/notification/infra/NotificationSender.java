package com.claimsflow.notification.infra;

import com.claimsflow.notification.domain.Notification;

/**
 * Port for the physical delivery channel.
 *
 * @throws RuntimeException on delivery failure — the dispatcher translates
 *         this into retry/dead-letter bookkeeping.
 */
public interface NotificationSender {

    void send(Notification notification);
}
