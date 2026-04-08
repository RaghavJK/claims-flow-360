package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.infra.messaging.ClaimEventPublisher;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Drives the claim state machine (FR-02). Each transition is persisted as an
 * immutable {@link ClaimEvent} and published for downstream projection.
 */
@Slf4j
@Service
public class WorkflowService {

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;
    private final ClaimEventPublisher eventPublisher;

    public WorkflowService(ClaimRepository claimRepository,
                           ClaimEventRepository claimEventRepository,
                           ClaimEventPublisher eventPublisher) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Claim transition(String claimRef, ClaimStatus target, String actorId, String reason) {
        Claim claim = claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));
        ClaimStatus from = claim.transitionTo(target);
        log.info("Claim {} transitioned {} -> {} by actor={}", claimRef, from, target, actorId);
        ClaimEvent event = claimEventRepository.save(
                ClaimEvent.transitioned(claim.getId(), from, target, actorId, reason));
        eventPublisher.publish(event);
        return claim;
    }

    @Transactional
    public Claim approveWithAmount(String claimRef, BigDecimal approvedAmount, String actorId) {
        Claim claim = transition(claimRef, ClaimStatus.APPROVED, actorId, "approved with amount " + approvedAmount);
        claim.approve(approvedAmount);
        return claim;
    }
}
