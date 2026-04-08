package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.messaging.ClaimEventPublisher;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import com.claimsflow.shared.exception.InvalidClaimTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class WorkflowServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock ClaimEventRepository claimEventRepository;
    @Mock ClaimEventPublisher eventPublisher;

    @InjectMocks WorkflowService service;

    private Claim existing;

    @BeforeEach
    void setUp() {
        existing = Claim.submit("CLM-1", "POL-1", "Jane", new BigDecimal("500"), null);
    }

    @Test
    void transitionPersistsEventAndPublishes() {
        when(claimRepository.findByClaimRef("CLM-1")).thenReturn(Optional.of(existing));
        when(claimEventRepository.save(any(ClaimEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.transition("CLM-1", ClaimStatus.UNDER_REVIEW, "adjuster-7", "starting review");

        assertThat(result.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        verify(claimEventRepository).save(any(ClaimEvent.class));
        verify(eventPublisher).publish(any(ClaimEvent.class));
    }

    @Test
    void throwsWhenClaimNotFound() {
        when(claimRepository.findByClaimRef("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition("missing", ClaimStatus.UNDER_REVIEW, "a", null))
                .isInstanceOf(ClaimNotFoundException.class);
    }

    @Test
    void rejectsIllegalTransition() {
        when(claimRepository.findByClaimRef("CLM-1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.transition("CLM-1", ClaimStatus.APPROVED, "a", null))
                .isInstanceOf(InvalidClaimTransitionException.class);
        verify(claimEventRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void approveWithAmountDrivesFullTransitionChain() {
        existing.transitionTo(ClaimStatus.UNDER_REVIEW);
        existing.transitionTo(ClaimStatus.ADJUDICATION);
        when(claimRepository.findByClaimRef("CLM-1")).thenReturn(Optional.of(existing));
        when(claimEventRepository.save(any(ClaimEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.approveWithAmount("CLM-1", new BigDecimal("450.00"), "adjuster-7");

        assertThat(result.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(result.getAmountApproved()).isEqualByComparingTo("450.00");
    }
}
