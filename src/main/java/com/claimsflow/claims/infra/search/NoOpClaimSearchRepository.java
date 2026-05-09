package com.claimsflow.claims.infra.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Test-profile stub: silently discards upserts and returns empty search results.
 * Keeps the Spring context loadable without a real OpenSearch cluster.
 */
@Slf4j
@Repository
@Profile("test")
public class NoOpClaimSearchRepository implements ClaimSearchRepository {

    @Override
    public void upsert(ClaimDocument document) {
        log.debug("[test-stub] OpenSearch upsert suppressed for claimRef={}", document.getClaimRef());
    }

    @Override
    public ClaimSearchResult search(ClaimSearchRequest request) {
        return new ClaimSearchResult(List.of(), 0L, request.page(), request.size());
    }
}
