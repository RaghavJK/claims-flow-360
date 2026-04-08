package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.domain.fraud.FraudIndicatorResult;
import com.claimsflow.claims.domain.fraud.FraudScoreResult;
import com.claimsflow.claims.domain.fraud.FraudScoringChain;
import com.claimsflow.claims.infra.messaging.ClaimEventPublisher;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimIngestionServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock ClaimEventRepository claimEventRepository;
    @Mock FraudScoringChain fraudScoringChain;
    @Mock ClaimEventPublisher eventPublisher;

    ClaimIngestionService service;

    @BeforeEach
    void setUp() {
        service = new ClaimIngestionService(claimRepository, claimEventRepository, fraudScoringChain, eventPublisher, 70);
    }

    @Test
    void ingestsClaimPersistsAndPublishesEvents() {
        when(claimRepository.existsByClaimRef(any())).thenReturn(false);
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(claimEventRepository.save(any(ClaimEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fraudScoringChain.score(any(Claim.class))).thenReturn(
                new FraudScoreResult(40, List.of(FraudIndicatorResult.triggered("X", 40, "r"))));

        ClaimIngestionService.IngestClaimCommand cmd = new ClaimIngestionService.IngestClaimCommand(
                "POL-99", "John Smith", new BigDecimal("1200.00"), "rear-end");

        Claim claim = service.ingest(cmd, "actor-1");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(claim.getFraudScore()).isEqualTo(40);
        assertThat(claim.getClaimRef()).startsWith("CLM-");

        ArgumentCaptor<ClaimEvent> eventCaptor = ArgumentCaptor.forClass(ClaimEvent.class);
        verify(claimEventRepository, times(2)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(ClaimEvent::getEventType)
                .containsExactly("CLAIM_SUBMITTED", "CLAIM_FRAUD_SCORED");
        verify(eventPublisher, times(2)).publish(any(ClaimEvent.class));
    }

    @Test
    void flagsHighFraudWithoutBlockingIngestion() {
        when(claimRepository.existsByClaimRef(any())).thenReturn(false);
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
        when(claimEventRepository.save(any(ClaimEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fraudScoringChain.score(any(Claim.class))).thenReturn(
                new FraudScoreResult(85, List.of(FraudIndicatorResult.triggered("X", 85, "r"))));

        Claim claim = service.ingest(new ClaimIngestionService.IngestClaimCommand(
                "POL-1", "J", new BigDecimal("1"), null), "a");

        assertThat(claim.getFraudScore()).isEqualTo(85);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }
}
