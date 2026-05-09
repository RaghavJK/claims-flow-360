package com.claimsflow.claims.infra.ai;

import com.claimsflow.claims.domain.Claim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BedrockClaimsSummarizerTest {

    @Mock BedrockRuntimeClient bedrockRuntimeClient;

    ClaimSummarizationPromptBuilder promptBuilder;
    BedrockClaimsSummarizer summarizer;

    @BeforeEach
    void setUp() {
        promptBuilder = new ClaimSummarizationPromptBuilder();
        summarizer = new BedrockClaimsSummarizer(
                bedrockRuntimeClient, promptBuilder,
                "anthropic.claude-3-haiku-20240307-v1:0", 500, 70);
    }

    @Test
    void summarizeCallsBedrockWithCorrectModelAndReturnsText() {
        Claim claim = Claim.submit("CLM-TEST", "POL-1", "Alice", new BigDecimal("1200"), "whiplash injury");
        claim.assignFraudScore(30);

        String expectedSummary = "{\"keyFacts\":\"Minor whiplash claim\",\"recommendedAction\":\"AUTO_APPROVE\"}";
        ConverseResponse mockResponse = buildMockResponse(expectedSummary, 120, 80);
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class))).thenReturn(mockResponse);

        String result = summarizer.summarize(claim);

        assertThat(result).isEqualTo(expectedSummary);
        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(bedrockRuntimeClient).converse(captor.capture());

        ConverseRequest req = captor.getValue();
        assertThat(req.modelId()).isEqualTo("anthropic.claude-3-haiku-20240307-v1:0");
        assertThat(req.messages().get(0).role()).isEqualTo(ConversationRole.USER);
        // Verify prompt contains key claim fields
        String prompt = req.messages().get(0).content().get(0).text();
        assertThat(prompt).contains("CLM-TEST").contains("POL-1").contains("1200");
    }

    @Test
    void promptBuilderIncludesFraudScoreAndThreshold() {
        Claim claim = Claim.submit("CLM-X", "POL-X", "Bob", new BigDecimal("999"), null);
        claim.assignFraudScore(80);
        String prompt = promptBuilder.build(claim, 70);
        assertThat(prompt).contains("80").contains("70"); // score and threshold
        assertThat(prompt).contains("keyFacts");          // JSON format instruction
        assertThat(prompt).contains("recommendedAction");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ConverseResponse buildMockResponse(String text, int inputTokens, int outputTokens) {
        ContentBlock content = ContentBlock.fromText(text);
        Message msg = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(content))
                .build();
        ConverseOutput output = ConverseOutput.builder().message(msg).build();
        TokenUsage usage = TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .build();
        return (ConverseResponse) ConverseResponse.builder()
                .output(output)
                .usage(usage)
                .stopReason(StopReason.END_TURN)
                .build();
    }
}
