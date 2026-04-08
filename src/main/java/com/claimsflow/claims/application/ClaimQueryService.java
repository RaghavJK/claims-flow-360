package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service. Week 1 reads from MySQL directly; Week 2 swaps this for
 * an OpenSearch-backed query service on the CQRS read model.
 */
@Service
@Transactional(readOnly = true)
public class ClaimQueryService {

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;

    public ClaimQueryService(ClaimRepository claimRepository, ClaimEventRepository claimEventRepository) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
    }

    public Claim getByRef(String claimRef) {
        return claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));
    }

    public List<ClaimEvent> getHistory(String claimRef) {
        Claim claim = getByRef(claimRef);
        return claimEventRepository.findByClaimIdOrderByOccurredAtAsc(claim.getId());
    }
}
