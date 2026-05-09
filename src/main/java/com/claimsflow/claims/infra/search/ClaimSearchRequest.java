package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.ClaimStatus;

/**
 * Parameters for the OpenSearch claims search endpoint.
 *
 * @param query      full-text query (claimantName, description, policyNumber)
 * @param status     filter by status (optional)
 * @param fraudOnly  restrict to fraud-flagged claims
 * @param page       0-based page number
 * @param size       page size (max 100)
 */
public record ClaimSearchRequest(
        String query,
        ClaimStatus status,
        boolean fraudOnly,
        int page,
        int size
) {
    public ClaimSearchRequest {
        if (size < 1 || size > 100) size = 20;
        if (page < 0) page = 0;
    }

    public int from() { return page * size; }
}
