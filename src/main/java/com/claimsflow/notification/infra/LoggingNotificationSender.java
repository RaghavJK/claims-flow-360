package com.claimsflow.notification.infra;

import com.claimsflow.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-profile sender — logs instead of publishing to SNS.
 */
@Slf4j
@Component
@Profile("test")
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        log.debug("[test] notification suppressed: channel={} recipient={} subject={}",
                notification.getChannel(), notification.getRecipient(), notification.getSubject());
    }
}
