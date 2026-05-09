package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ai.AiSummary;
import com.claimsflow.claims.domain.ai.AiSummaryRepository;
import com.claimsflow.claims.infra.ai.ClaimsSummarizer;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.claims.infra.search.ClaimProjectionService;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Bedrock AI summarization for a claim:
 * 1. Calls {@link ClaimsSummarizer#summarize(Claim)} (Bedrock Claude Converse API)
 * 2. Persists the result in ai_summaries (upsert)
 * 3. Refreshes the OpenSearch projection so the summary is searchable
 *
 * <p>FR-03 from the ClaimsFlow360 spec. Exposed via
 * {@code POST /api/v1/claims/{ref}/summarize} (manual trigger in Week 2;
 * automated async trigger via SQS consumer in Week 3).
 */
@Slf4j
@Service
public class AiSummarizationService {

    private final ClaimRepository claimRepository;
    private final AiSummaryRepository aiSummaryRepository;
    private final ClaimsSummarizer summarizer;
    private final ClaimProjectionService projectionService;
    private final String modelId;

    public AiSummarizationService(ClaimRepository claimRepository,
                                   AiSummaryRepository aiSummaryRepository,
                                   ClaimsSummarizer summarizer,
                                   ClaimProjectionService projectionService,
                                   @Value("${claimsflow.ai.bedrock.model-id:anthropic.claude-3-haiku-20240307-v1:0}") String modelId) {
        this.claimRepository = claimRepository;
        this.aiSummaryRepository = aiSummaryRepository;
        this.summarizer = summarizer;
        this.projectionService = projectionService;
        this.modelId = modelId;
    }

    @Transactional
    public AiSummary summarize(String claimRef) {
        Claim claim = claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));

        log.info("Requesting AI summary for claim {} via model {}", claimRef, modelId);
        String rawSummary = summarizer.summarize(claim);

        AiSummary saved = aiSummaryRepository.findByClaimRef(claimRef)
                .map(existing -> {
                    existing.updateSummary(rawSummary);
                    return aiSummaryRepository.save(existing);
                })
                .orElseGet(() -> aiSummaryRepository.save(
                        AiSummary.create(claim.getId(), claimRef, modelId, rawSummary)));

        projectionService.updateSummary(claimRef, rawSummary);

        log.info("AI summary persisted and projected for claim {}", claimRef);
        return saved;
    }
}
