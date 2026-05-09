package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * AWS Bedrock Runtime client. Used by {@link com.claimsflow.claims.infra.ai.BedrockClaimsSummarizer}
 * to invoke Claude via the Converse API.
 *
 * <p>Excluded from test profile — tests inject a {@code @MockBean BedrockRuntimeClient}.
 */
@Slf4j
@Configuration
@Profile("!test")
public class BedrockConfig {

    @Value("${claimsflow.aws.region:ap-south-1}")
    private String region;

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        log.info("Initialising BedrockRuntimeClient for region={}", region);
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
