package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ai.AiSummary;
import com.claimsflow.claims.domain.ai.AiSummaryRepository;
import com.claimsflow.claims.infra.ai.ClaimsSummarizer;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.claims.infra.search.ClaimProjectionService;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiSummarizationServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock AiSummaryRepository aiSummaryRepository;
    @Mock ClaimsSummarizer summarizer;
    @Mock ClaimProjectionService projectionService;

    AiSummarizationService service;
    private Claim sampleClaim;

    @BeforeEach
    void setUp() {
        service = new AiSummarizationService(
                claimRepository, aiSummaryRepository, summarizer, projectionService,
                "anthropic.claude-3-haiku-20240307-v1:0");
        sampleClaim = Claim.submit("CLM-50", "POL-50", "Mary", new BigDecimal("3000"), "flood");
    }

    @Test
    void summarizeCreatesNewAiSummaryWhenNoneExists() {
        when(claimRepository.findByClaimRef("CLM-50")).thenReturn(Optional.of(sampleClaim));
        when(summarizer.summarize(sampleClaim)).thenReturn("{\"keyFacts\":\"flood claim\"}");
        when(aiSummaryRepository.findByClaimRef("CLM-50")).thenReturn(Optional.empty());
        when(aiSummaryRepository.save(any(AiSummary.class))).thenAnswer(inv -> inv.getArgument(0));

        AiSummary result = service.summarize("CLM-50");

        assertThat(result.getClaimRef()).isEqualTo("CLM-50");
        assertThat(result.getSummary()).contains("flood claim");
        verify(projectionService).updateSummary(eq("CLM-50"), anyString());
    }

    @Test
    void summarizeUpdatesExistingSummary() {
        AiSummary existing = AiSummary.create(1L, "CLM-50", "haiku", "{\"keyFacts\":\"old\"}");
        when(claimRepository.findByClaimRef("CLM-50")).thenReturn(Optional.of(sampleClaim));
        when(summarizer.summarize(sampleClaim)).thenReturn("{\"keyFacts\":\"updated flood\"}");
        when(aiSummaryRepository.findByClaimRef("CLM-50")).thenReturn(Optional.of(existing));
        when(aiSummaryRepository.save(any(AiSummary.class))).thenAnswer(inv -> inv.getArgument(0));

        AiSummary result = service.summarize("CLM-50");

        assertThat(result.getSummary()).contains("updated flood");
        verify(aiSummaryRepository, times(1)).save(existing);
    }

    @Test
    void summarizeThrowsWhenClaimNotFound() {
        when(claimRepository.findByClaimRef("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.summarize("UNKNOWN"))
                .isInstanceOf(ClaimNotFoundException.class);
        verifyNoInteractions(summarizer, aiSummaryRepository, projectionService);
    }
}
