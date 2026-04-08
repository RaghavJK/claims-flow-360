package com.claimsflow.claims.infra.persistence;

import com.claimsflow.claims.domain.ClaimEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClaimEventRepository extends JpaRepository<ClaimEvent, Long> {

    List<ClaimEvent> findByClaimIdOrderByOccurredAtAsc(Long claimId);
}
