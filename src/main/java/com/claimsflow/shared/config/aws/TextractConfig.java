package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;

/**
 * Textract client for document OCR (Week 4).
 * Excluded from tests — {@code StubOcrEngine} covers the test profile.
 */
@Slf4j
@Configuration
@Profile("!test")
public class TextractConfig {

    @Value("${claimsflow.aws.region:ap-south-1}")
    private String region;

    @Bean
    public TextractClient textractClient() {
        log.info("Initialising TextractClient for region={}", region);
        return TextractClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
