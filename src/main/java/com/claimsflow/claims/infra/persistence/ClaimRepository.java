package com.claimsflow.claims.infra.persistence;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClaimRepository extends JpaRepository<Claim, Long> {

    Optional<Claim> findByClaimRef(String claimRef);

    boolean existsByClaimRef(String claimRef);

    long countByPolicyNumberAndCreatedAtAfterAndIdNot(String policyNumber, Instant since, Long excludeId);

    /** Customer360: all claims for a policyholder, newest first. */
    List<Claim> findByPolicyNumberOrderByCreatedAtDesc(String policyNumber);

    /** Reconciliation: claims changed since the last run window. */
    List<Claim> findByUpdatedAtAfter(Instant since);

    /** Dashboard: workload distribution across the workflow. */
    long countByStatus(ClaimStatus status);

    /** Dashboard: claims at or above the SIU fraud threshold. */
    long countByFraudScoreGreaterThanEqual(int threshold);

    /** Dashboard: portfolio-wide average fraud score (null when no scored claims). */
    @Query("select avg(c.fraudScore) from Claim c where c.fraudScore is not null")
    Double averageFraudScore();
}
