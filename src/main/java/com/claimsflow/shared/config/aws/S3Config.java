package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigner for direct-to-S3 document uploads (Week 4).
 * Excluded from tests — {@code StubDocumentStorage} covers the test profile.
 */
@Slf4j
@Configuration
@Profile("!test")
public class S3Config {

    @Value("${claimsflow.aws.region:ap-south-1}")
    private String region;

    @Bean
    public S3Presigner s3Presigner() {
        log.info("Initialising S3Presigner for region={}", region);
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
