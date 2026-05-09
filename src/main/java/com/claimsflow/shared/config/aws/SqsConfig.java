package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SQS client bean. Uses the standard AWS Default Credential Provider Chain:
 * environment variables → ~/.aws/credentials → IAM instance profile.
 *
 * <p>Excluded from the test profile — tests inject a {@code @MockBean SqsClient}.
 */
@Slf4j
@Configuration
@Profile("!test")
public class SqsConfig {

    @Value("${claimsflow.aws.region:ap-south-1}")
    private String region;

    @Bean
    public SqsClient sqsClient() {
        log.info("Initialising SqsClient for region={}", region);
        return SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
