package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

/**
 * Calls AWS Bedrock Claude via the <b>Converse API</b> to generate a structured
 * JSON summary for a claim.
 *
 * <p>The Converse API is model-agnostic — swapping the modelId (e.g. Claude Haiku
 * for cost, Claude Sonnet for accuracy) requires no code change.
 *
 * <p>Resilience4j annotations apply circuit breaker + retry before Bedrock is called.
 * The fallback returns a degraded-mode string so ingestion is never blocked.
 *
 * <p>Only active in non-test profiles (real BedrockRuntimeClient required).
 */
@Slf4j
@Component
@Profile("!test")
public class BedrockClaimsSummarizer implements ClaimsSummarizer {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ClaimSummarizationPromptBuilder promptBuilder;
    private final String modelId;
    private final int maxTokens;
    private final int fraudThreshold;

    public BedrockClaimsSummarizer(BedrockRuntimeClient bedrockRuntimeClient,
                                   ClaimSummarizationPromptBuilder promptBuilder,
                                   @Value("${claimsflow.ai.bedrock.model-id:anthropic.claude-3-haiku-20240307-v1:0}") String modelId,
                                   @Value("${claimsflow.ai.bedrock.max-tokens:500}") int maxTokens,
                                   @Value("${claimsflow.fraud.threshold:70}") int fraudThreshold) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.promptBuilder = promptBuilder;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.fraudThreshold = fraudThreshold;
    }

    @Override
    @CircuitBreaker(name = "bedrockSummarizer", fallbackMethod = "fallbackSummary")
    @Retry(name = "bedrockSummarizer")
    public String summarize(Claim claim) {
        String prompt = promptBuilder.build(claim, fraudThreshold);

        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(prompt))
                        .build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(maxTokens)
                        .temperature(0.2f)      // low temperature for deterministic structured output
                        .build())
                .build();

        ConverseResponse response = bedrockRuntimeClient.converse(request);

        String summary = response.output().message().content().get(0).text();
        int inputTokens  = response.usage().inputTokens();
        int outputTokens = response.usage().outputTokens();

        log.info("Bedrock summary generated claimRef={} model={} tokens={}/{}",
                claim.getClaimRef(), modelId, inputTokens, outputTokens);

        return summary;
    }

    @SuppressWarnings("unused")
    private String fallbackSummary(Claim claim, Throwable ex) {
        log.warn("Bedrock summarizer circuit open / retry exhausted for claim {}: {}",
                claim.getClaimRef(), ex.getMessage());
        return """
                {"keyFacts":"Summary unavailable","coverageMatch":"N/A",
                 "redFlags":"N/A","recommendedAction":"MANUAL_REVIEW"}""";
    }
}
