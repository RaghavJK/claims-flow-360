package com.claimsflow.shared.config.aws;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenSearch Java client wired to the CQRS read-model cluster.
 *
 * <p>For local dev, points at a local OpenSearch instance (http://localhost:9200)
 * or an AWS OpenSearch Service domain endpoint.
 *
 * <p>Excluded from the test profile — the {@code NoOpClaimSearchRepository} handles
 * test-profile search calls without a running cluster.
 */
@Slf4j
@Configuration
@Profile("!test")
public class OpenSearchConfig {

    @Value("${claimsflow.opensearch.endpoint:http://localhost:9200}")
    private String endpoint;

    @Bean
    public OpenSearchClient openSearchClient() throws Exception {
        log.info("Initialising OpenSearchClient for endpoint={}", endpoint);
        HttpHost host = HttpHost.create(endpoint);
        var transport = ApacheHttpClient5TransportBuilder.builder(host).build();
        return new OpenSearchClient(transport);
    }
}
