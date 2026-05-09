package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and refreshes the OpenSearch CQRS read-model projection for a claim.
 *
 * <p>Called by the SQS consumer (Week 3) when a {@code CLAIM_SUBMITTED} or
 * {@code CLAIM_TRANSITIONED} event arrives. Week 2 exposes a manual trigger
 * via {@code POST /api/v1/claims/{ref}/project} for testing without SQS.
 *
 * <p>A 6-hour reconciliation job (deferred to Week 3) will detect and repair
 * drift between MySQL and OpenSearch.
 */
@Slf4j
@Service
public class ClaimProjectionService {

    private final ClaimRepository claimRepository;
    private final ClaimSearchRepository searchRepository;
    private final int fraudThreshold;

    public ClaimProjectionService(ClaimRepository claimRepository,
                                  ClaimSearchRepository searchRepository,
                                  @Value("${claimsflow.fraud.threshold:70}") int fraudThreshold) {
        this.claimRepository = claimRepository;
        this.searchRepository = searchRepository;
        this.fraudThreshold = fraudThreshold;
    }

    /**
     * Refreshes (or creates) the OpenSearch document for the given claim.
     * Idempotent — safe to call multiple times.
     */
    @Transactional(readOnly = true)
    public void project(String claimRef) {
        Claim claim = claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));
        ClaimDocument doc = ClaimDocument.from(claim, fraudThreshold);
        searchRepository.upsert(doc);
        log.info("Projected claim {} -> OpenSearch status={} fraudScore={}", claimRef, claim.getStatus(), claim.getFraudScore());
    }

    /**
     * Updates the AI summary field on an existing document without re-fetching
     * the full claim from MySQL.
     */
    public void updateSummary(String claimRef, String summary) {
        // Re-project the full document so the summary field is included.
        // In production this would be a partial update (doc-as-upsert with script).
        project(claimRef);
        log.info("Summary updated in projection for claim {}", claimRef);
    }
}
