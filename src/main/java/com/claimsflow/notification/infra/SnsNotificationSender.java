package com.claimsflow.notification.infra;

import com.claimsflow.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

/**
 * SNS-backed sender: publishes to a single topic with {@code channel} and
 * {@code recipient} message attributes. Channel-specific subscribers (SES
 * email, SMS, in-app WebSocket bridge) filter on the {@code channel}
 * attribute — SNS fanout does the multiplexing, not application code.
 */
@Slf4j
@Component
@Profile("!test")
public class SnsNotificationSender implements NotificationSender {

    private final SnsClient snsClient;
    private final String topicArn;

    public SnsNotificationSender(SnsClient snsClient,
                                 @Value("${claimsflow.aws.sns.notifications-topic-arn}") String topicArn) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
    }

    @Override
    public void send(Notification notification) {
        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .subject(notification.getSubject())
                .message(notification.getBody())
                .messageAttributes(Map.of(
                        "channel", stringAttr(notification.getChannel().name()),
                        "recipient", stringAttr(notification.getRecipient()),
                        "claimRef", stringAttr(notification.getClaimRef())))
                .build());
        log.debug("SNS published notification id={} channel={} claimRef={}",
                notification.getId(), notification.getChannel(), notification.getClaimRef());
    }

    private MessageAttributeValue stringAttr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
