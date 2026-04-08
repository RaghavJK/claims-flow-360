package com.claimsflow.claims.infra.persistence;

import com.claimsflow.claims.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByClaimRef(String claimRef);

    boolean existsByClaimRef(String claimRef);

    long countByPolicyNumberAndCreatedAtAfterAndIdNot(String policyNumber, Instant since, Long excludeId);
}
