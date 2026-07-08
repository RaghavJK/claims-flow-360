package com.claimsflow.dashboard;

import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Computes the live dashboard snapshot from the MySQL write model.
 *
 * <p>Counts come from indexed {@code status} queries — cheap at current scale.
 * Once claim volume makes six COUNT queries per snapshot noticeable, the
 * upgrade path is a single OpenSearch {@code terms} aggregation over the read
 * model (accepting its ~2 s staleness, which is fine for a dashboard).
 */
@Service
public class DashboardMetricsService {

    private final ClaimRepository claimRepository;
    private final int fraudThreshold;

    public DashboardMetricsService(ClaimRepository claimRepository,
                                   @Value("${claimsflow.fraud.threshold:70}") int fraudThreshold) {
        this.claimRepository = claimRepository;
        this.fraudThreshold = fraudThreshold;
    }

    @Transactional(readOnly = true)
    public ClaimDashboardMetrics snapshot() {
        long submitted = claimRepository.countByStatus(ClaimStatus.SUBMITTED);
        long underReview = claimRepository.countByStatus(ClaimStatus.UNDER_REVIEW);
        long adjudication = claimRepository.countByStatus(ClaimStatus.ADJUDICATION);
        long approved = claimRepository.countByStatus(ClaimStatus.APPROVED);
        long denied = claimRepository.countByStatus(ClaimStatus.DENIED);

        long terminal = approved + denied;
        double approvalRate = terminal == 0 ? 0.0 : (double) approved / terminal;

        Double avgFraud = claimRepository.averageFraudScore();

        return new ClaimDashboardMetrics(
                submitted + underReview + adjudication + approved + denied,
                submitted,
                underReview,
                adjudication,
                approved,
                denied,
                approvalRate,
                claimRepository.countByFraudScoreGreaterThanEqual(fraudThreshold),
                avgFraud != null ? avgFraud : 0.0,
                Instant.now());
    }
}
