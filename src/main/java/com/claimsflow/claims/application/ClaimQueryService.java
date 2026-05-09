package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.claims.infra.search.ClaimSearchRepository;
import com.claimsflow.claims.infra.search.ClaimSearchRequest;
import com.claimsflow.claims.infra.search.ClaimSearchResult;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service.
 *
 * <p>Week 1: all reads from MySQL.
 * Week 2: search now delegates to the OpenSearch CQRS read model, while
 * point-reads (by ref) stay on MySQL as the authoritative write store.
 */
@Service
@Transactional(readOnly = true)
public class ClaimQueryService {

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;
    private final ClaimSearchRepository searchRepository;

    public ClaimQueryService(ClaimRepository claimRepository,
                             ClaimEventRepository claimEventRepository,
                             ClaimSearchRepository searchRepository) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.searchRepository = searchRepository;
    }

    /** Point-read from MySQL (write model = authoritative). */
    public Claim getByRef(String claimRef) {
        return claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));
    }

    /** Ordered audit log from MySQL. */
    public List<ClaimEvent> getHistory(String claimRef) {
        Claim claim = getByRef(claimRef);
        return claimEventRepository.findByClaimIdOrderByOccurredAtAsc(claim.getId());
    }

    /**
     * Full-text + faceted search via the OpenSearch CQRS read model.
     * Eventual consistency: up to ~2 s lag after a write before the projection
     * is updated. The caller should not use this for authoritative checks.
     */
    public ClaimSearchResult search(ClaimSearchRequest request) {
        return searchRepository.search(request);
    }
}
