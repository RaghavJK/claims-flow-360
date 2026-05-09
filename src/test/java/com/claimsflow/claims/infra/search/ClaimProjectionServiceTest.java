package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimProjectionServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock ClaimSearchRepository searchRepository;

    ClaimProjectionService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ClaimProjectionService(claimRepository, searchRepository, 70);
    }

    @Test
    void projectBuildsAndUpsertsDocumentWithCorrectFields() {
        Claim claim = Claim.submit("CLM-123", "POL-1", "Jane Doe", new BigDecimal("5000"), "accident");
        claim.assignFraudScore(40);
        when(claimRepository.findByClaimRef("CLM-123")).thenReturn(Optional.of(claim));

        service.project("CLM-123");

        ArgumentCaptor<ClaimDocument> captor = ArgumentCaptor.forClass(ClaimDocument.class);
        verify(searchRepository).upsert(captor.capture());

        ClaimDocument doc = captor.getValue();
        assertThat(doc.getClaimRef()).isEqualTo("CLM-123");
        assertThat(doc.getPolicyNumber()).isEqualTo("POL-1");
        assertThat(doc.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(doc.getFraudScore()).isEqualTo(40);
        assertThat(doc.isFraudFlagged()).isFalse(); // 40 < 70 threshold
    }

    @Test
    void projectFlagsFraudWhenScoreAtOrAboveThreshold() {
        Claim claim = Claim.submit("CLM-999", "POL-2", "John", new BigDecimal("9000"), null);
        claim.assignFraudScore(75); // above threshold
        when(claimRepository.findByClaimRef("CLM-999")).thenReturn(Optional.of(claim));

        service.project("CLM-999");

        ArgumentCaptor<ClaimDocument> captor = ArgumentCaptor.forClass(ClaimDocument.class);
        verify(searchRepository).upsert(captor.capture());
        assertThat(captor.getValue().isFraudFlagged()).isTrue();
    }

    @Test
    void projectThrowsWhenClaimNotFound() {
        when(claimRepository.findByClaimRef("CLM-MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.project("CLM-MISSING"))
                .isInstanceOf(ClaimNotFoundException.class);
        verify(searchRepository, never()).upsert(any());
    }
}
