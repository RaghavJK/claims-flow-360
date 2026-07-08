package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodic drift repair between the MySQL write model and the OpenSearch
 * read model.
 *
 * <p>The SQS pipeline keeps the projection fresh in normal operation, but an
 * eventually-consistent system needs a safety net: a poison message deleted
 * without projecting, an OpenSearch outage outlasting SQS message retention,
 * or a bug in the consumer all leave silent drift. This job re-projects every
 * claim updated within the lookback window — the projection upsert is
 * idempotent, so over-projecting is harmless.
 *
 * <p>Lookback (7h) deliberately overlaps the interval (6h) so a claim updated
 * moments before a run is still covered by the next one.
 */
@Slf4j
@Component
@Profile("!test")
public class SearchReconciliationJob {

    private final ClaimRepository claimRepository;
    private final ClaimProjectionService projectionService;
    private final int lookbackHours;

    public SearchReconciliationJob(ClaimRepository claimRepository,
                                   ClaimProjectionService projectionService,
                                   @Value("${claimsflow.reconciliation.lookback-hours:7}") int lookbackHours) {
        this.claimRepository = claimRepository;
        this.projectionService = projectionService;
        this.lookbackHours = lookbackHours;
    }

    @Scheduled(
            fixedDelayString = "${claimsflow.reconciliation.interval-ms:21600000}",   // 6 hours
            initialDelayString = "${claimsflow.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));
        List<Claim> changed = claimRepository.findByUpdatedAtAfter(since);
        if (changed.isEmpty()) {
            log.info("Reconciliation: no claims updated in the last {}h — nothing to repair", lookbackHours);
            return;
        }

        int repaired = 0;
        int failed = 0;
        for (Claim claim : changed) {
            try {
                projectionService.project(claim.getClaimRef());
                repaired++;
            } catch (RuntimeException ex) {
                // One bad claim must not abort the sweep — log and continue.
                failed++;
                log.error("Reconciliation failed for claim {}: {}", claim.getClaimRef(), ex.getMessage());
            }
        }
        log.info("Reconciliation complete: {} re-projected, {} failed (window={}h)",
                repaired, failed, lookbackHours);
    }
}
