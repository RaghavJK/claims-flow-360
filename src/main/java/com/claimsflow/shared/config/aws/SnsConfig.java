package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * SNS client for the notification fanout topic (Week 4).
 * Excluded from tests — {@code LoggingNotificationSender} covers the test profile.
 */
@Slf4j
@Configuration
@Profile("!test")
public class SnsConfig {

    @Value("${claimsflow.aws.region:ap-south-1}")
    private String region;

    @Bean
    public SnsClient snsClient() {
        log.info("Initialising SnsClient for region={}", region);
        return SnsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
