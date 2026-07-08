package com.claimsflow.customer.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.customer.domain.Customer360View;
import com.claimsflow.shared.exception.CustomerNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the Customer360 aggregated policyholder view (FR-05).
 *
 * <p>The Customer bounded context reads the Claims context only through its
 * repository port — no direct entity mutation crosses the boundary. A
 * policyholder's claim list is small (tens, not millions), so the aggregation
 * runs in-memory over one indexed query rather than pushing GROUP BY into SQL;
 * portfolio-wide analytics belong to OpenSearch aggregations instead.
 */
@Slf4j
@Service
public class Customer360Service {

    private static final int RECENT_CLAIMS_LIMIT = 5;

    private final ClaimRepository claimRepository;
    private final int fraudThreshold;

    public Customer360Service(ClaimRepository claimRepository,
                              @Value("${claimsflow.fraud.threshold:70}") int fraudThreshold) {
        this.claimRepository = claimRepository;
        this.fraudThreshold = fraudThreshold;
    }

    @Transactional(readOnly = true)
    public Customer360View view(String policyNumber) {
        List<Claim> claims = claimRepository.findByPolicyNumberOrderByCreatedAtDesc(policyNumber);
        if (claims.isEmpty()) {
            throw new CustomerNotFoundException(policyNumber);
        }

        Map<ClaimStatus, Long> byStatus = new EnumMap<>(ClaimStatus.class);
        BigDecimal totalClaimed = BigDecimal.ZERO;
        BigDecimal totalApproved = BigDecimal.ZERO;
        long fraudFlagged = 0;
        long scoredCount = 0;
        long scoreSum = 0;

        for (Claim claim : claims) {
            byStatus.merge(claim.getStatus(), 1L, Long::sum);
            totalClaimed = totalClaimed.add(claim.getAmountClaimed());
            if (claim.getAmountApproved() != null) {
                totalApproved = totalApproved.add(claim.getAmountApproved());
            }
            Integer score = claim.getFraudScore();
            if (score != null) {
                scoredCount++;
                scoreSum += score;
                if (score >= fraudThreshold) {
                    fraudFlagged++;
                }
            }
        }

        Instant firstClaimAt = claims.stream().map(Claim::getCreatedAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant lastClaimAt = claims.stream().map(Claim::getCreatedAt).max(Comparator.naturalOrder()).orElseThrow();
        double averageFraudScore = scoredCount == 0 ? 0.0 : (double) scoreSum / scoredCount;

        List<Customer360View.ClaimSnapshot> recent = claims.stream()
                .limit(RECENT_CLAIMS_LIMIT)
                .map(c -> new Customer360View.ClaimSnapshot(
                        c.getClaimRef(), c.getStatus(), c.getAmountClaimed(), c.getFraudScore(), c.getCreatedAt()))
                .toList();

        log.debug("Customer360 assembled for policy={} claims={} fraudFlagged={}",
                policyNumber, claims.size(), fraudFlagged);

        return new Customer360View(
                policyNumber,
                claims.size(),
                byStatus,
                totalClaimed,
                totalApproved,
                fraudFlagged,
                averageFraudScore,
                firstClaimAt,
                lastClaimAt,
                recent);
    }
}
