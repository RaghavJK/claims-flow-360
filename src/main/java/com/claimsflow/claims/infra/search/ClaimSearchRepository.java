package com.claimsflow.claims.infra.search;

/**
 * Port for the OpenSearch-backed CQRS read model.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@code !test} → {@link OpenSearchClaimSearchRepository} (real OpenSearch queries)</li>
 *   <li>{@code test}  → {@link NoOpClaimSearchRepository} (no-op, tests mock at service level)</li>
 * </ul>
 */
public interface ClaimSearchRepository {

    /** Upsert the claim document (create or replace by claimRef). */
    void upsert(ClaimDocument document);

    /** Faceted search across the claims index. */
    ClaimSearchResult search(ClaimSearchRequest request);
}
