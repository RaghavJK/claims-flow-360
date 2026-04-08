package com.claimsflow.claims.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.fraud.FraudScoreResult;
import com.claimsflow.claims.domain.fraud.FraudScoringChain;
import com.claimsflow.claims.infra.messaging.ClaimEventPublisher;
import com.claimsflow.claims.infra.persistence.ClaimEventRepository;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.shared.exception.DuplicateClaimException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ingests new claims: validates, persists, runs fraud scoring, and emits
 * domain events for CQRS projection.
 *
 * <p>FR-01 from the spec. The document classification + Bedrock-driven
 * deduplication is deferred to later weeks.
 */
@Slf4j
@Service
public class ClaimIngestionService {

    private final ClaimRepository claimRepository;
    private final ClaimEventRepository claimEventRepository;
    private final FraudScoringChain fraudScoringChain;
    private final ClaimEventPublisher eventPublisher;
    private final int fraudThreshold;

    public ClaimIngestionService(ClaimRepository claimRepository,
                                 ClaimEventRepository claimEventRepository,
                                 FraudScoringChain fraudScoringChain,
                                 ClaimEventPublisher eventPublisher,
                                 @Value("${claimsflow.fraud.threshold:70}") int fraudThreshold) {
        this.claimRepository = claimRepository;
        this.claimEventRepository = claimEventRepository;
        this.fraudScoringChain = fraudScoringChain;
        this.eventPublisher = eventPublisher;
        this.fraudThreshold = fraudThreshold;
    }

    @Transactional
    public Claim ingest(IngestClaimCommand cmd, String actorId) {
        String claimRef = generateClaimRef();
        if (claimRepository.existsByClaimRef(claimRef)) {
            throw new DuplicateClaimException("generated claim ref already exists: " + claimRef);
        }

        Claim claim = Claim.submit(
                claimRef,
                cmd.policyNumber(),
                cmd.claimantName(),
                cmd.amountClaimed(),
                cmd.description());
        Claim saved = claimRepository.save(claim);

        FraudScoreResult fraudResult = fraudScoringChain.score(saved);
        saved.assignFraudScore(fraudResult.totalScore());

        ClaimEvent submitted = claimEventRepository.save(ClaimEvent.submitted(saved.getId(), actorId));
        ClaimEvent fraudEvent = claimEventRepository.save(
                ClaimEvent.fraudScored(saved.getId(), actorId,
                        "total=%d breakdown=%s".formatted(fraudResult.totalScore(), fraudResult.indicatorResults())));

        eventPublisher.publish(submitted);
        eventPublisher.publish(fraudEvent);

        if (fraudResult.exceeds(fraudThreshold)) {
            log.warn("Claim {} scored {} >= threshold {}; flagged for SIU review",
                    saved.getClaimRef(), fraudResult.totalScore(), fraudThreshold);
        }
        return saved;
    }

    private String generateClaimRef() {
        return "CLM-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
    }

    public record IngestClaimCommand(
            String policyNumber,
            String claimantName,
            BigDecimal amountClaimed,
            String description
    ) {}
}
