package com.claimsflow.customer.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.customer.domain.Customer360View;
import com.claimsflow.shared.exception.CustomerNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Customer360ServiceTest {

    private static final int FRAUD_THRESHOLD = 70;

    @Mock ClaimRepository claimRepository;

    Customer360Service service;

    @BeforeEach
    void setUp() {
        service = new Customer360Service(claimRepository, FRAUD_THRESHOLD);
    }

    @Test
    void aggregatesCountsTotalsAndFraudExposure() {
        Claim open = Claim.submit("CLM-1", "POL-9", "Jane", new BigDecimal("1000"), null);
        open.assignFraudScore(20);

        Claim flagged = Claim.submit("CLM-2", "POL-9", "Jane", new BigDecimal("60000"), null);
        flagged.assignFraudScore(80); // >= threshold

        Claim settled = Claim.submit("CLM-3", "POL-9", "Jane", new BigDecimal("2000"), null);
        settled.assignFraudScore(10);
        settled.transitionTo(ClaimStatus.UNDER_REVIEW);
        settled.transitionTo(ClaimStatus.ADJUDICATION);
        settled.transitionTo(ClaimStatus.APPROVED);
        settled.approve(new BigDecimal("1800"));

        when(claimRepository.findByPolicyNumberOrderByCreatedAtDesc("POL-9"))
                .thenReturn(List.of(settled, flagged, open));

        Customer360View view = service.view("POL-9");

        assertThat(view.policyNumber()).isEqualTo("POL-9");
        assertThat(view.totalClaims()).isEqualTo(3);
        assertThat(view.claimsByStatus())
                .containsEntry(ClaimStatus.SUBMITTED, 2L)
                .containsEntry(ClaimStatus.APPROVED, 1L);
        assertThat(view.totalAmountClaimed()).isEqualByComparingTo("63000");
        assertThat(view.totalAmountApproved()).isEqualByComparingTo("1800");
        assertThat(view.fraudFlaggedCount()).isEqualTo(1);
        assertThat(view.averageFraudScore()).isCloseTo((20 + 80 + 10) / 3.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(view.firstClaimAt()).isNotNull();
        assertThat(view.lastClaimAt()).isNotNull();
    }

    @Test
    void recentClaimsAreCappedAtFiveNewestFirst() {
        List<Claim> seven = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> Claim.submit("CLM-" + i, "POL-9", "Jane", new BigDecimal("100"), null))
                .toList();
        when(claimRepository.findByPolicyNumberOrderByCreatedAtDesc("POL-9")).thenReturn(seven);

        Customer360View view = service.view("POL-9");

        assertThat(view.totalClaims()).isEqualTo(7);
        assertThat(view.recentClaims()).hasSize(5);
        // Repository returns newest-first; the snapshot strip preserves that order
        assertThat(view.recentClaims().get(0).claimRef()).isEqualTo("CLM-1");
    }

    @Test
    void unscoredClaimsProduceZeroAverageWithoutDivisionByZero() {
        Claim unscored = Claim.submit("CLM-1", "POL-9", "Jane", new BigDecimal("500"), null);
        when(claimRepository.findByPolicyNumberOrderByCreatedAtDesc("POL-9"))
                .thenReturn(List.of(unscored));

        Customer360View view = service.view("POL-9");

        assertThat(view.averageFraudScore()).isZero();
        assertThat(view.fraudFlaggedCount()).isZero();
    }

    @Test
    void unknownPolicyThrowsCustomerNotFound() {
        when(claimRepository.findByPolicyNumberOrderByCreatedAtDesc("POL-GHOST"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.view("POL-GHOST"))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("POL-GHOST");
    }
}
