package com.claimsflow.dashboard;

import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardMetricsServiceTest {

    private static final int FRAUD_THRESHOLD = 70;

    @Mock ClaimRepository claimRepository;

    DashboardMetricsService service;

    @BeforeEach
    void setUp() {
        service = new DashboardMetricsService(claimRepository, FRAUD_THRESHOLD);
    }

    @Test
    void snapshotAggregatesStatusCountsAndRates() {
        when(claimRepository.countByStatus(ClaimStatus.SUBMITTED)).thenReturn(10L);
        when(claimRepository.countByStatus(ClaimStatus.UNDER_REVIEW)).thenReturn(5L);
        when(claimRepository.countByStatus(ClaimStatus.ADJUDICATION)).thenReturn(2L);
        when(claimRepository.countByStatus(ClaimStatus.APPROVED)).thenReturn(6L);
        when(claimRepository.countByStatus(ClaimStatus.DENIED)).thenReturn(2L);
        when(claimRepository.countByFraudScoreGreaterThanEqual(FRAUD_THRESHOLD)).thenReturn(3L);
        when(claimRepository.averageFraudScore()).thenReturn(23.5);

        ClaimDashboardMetrics metrics = service.snapshot();

        assertThat(metrics.totalClaims()).isEqualTo(25);
        assertThat(metrics.submitted()).isEqualTo(10);
        assertThat(metrics.underReview()).isEqualTo(5);
        assertThat(metrics.adjudication()).isEqualTo(2);
        assertThat(metrics.approved()).isEqualTo(6);
        assertThat(metrics.denied()).isEqualTo(2);
        assertThat(metrics.approvalRate()).isEqualTo(6.0 / 8.0);
        assertThat(metrics.fraudFlaggedCount()).isEqualTo(3);
        assertThat(metrics.averageFraudScore()).isEqualTo(23.5);
        assertThat(metrics.generatedAt()).isNotNull();
    }

    @Test
    void emptyPortfolioProducesZeroRatesWithoutDivisionByZero() {
        when(claimRepository.countByStatus(org.mockito.ArgumentMatchers.any(ClaimStatus.class))).thenReturn(0L);
        when(claimRepository.countByFraudScoreGreaterThanEqual(FRAUD_THRESHOLD)).thenReturn(0L);
        when(claimRepository.averageFraudScore()).thenReturn(null); // avg over zero rows is SQL NULL

        ClaimDashboardMetrics metrics = service.snapshot();

        assertThat(metrics.totalClaims()).isZero();
        assertThat(metrics.approvalRate()).isZero();
        assertThat(metrics.averageFraudScore()).isZero();
    }
}
